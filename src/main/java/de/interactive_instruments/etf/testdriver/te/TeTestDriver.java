/**
 * Copyright 2010-2017 interactive instruments GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.interactive_instruments.etf.testdriver.te;

import static de.interactive_instruments.etf.EtfConstants.ETF_DATA_STORAGE_NAME;
import static de.interactive_instruments.etf.testdriver.te.TeTestDriver.TE_TEST_DRIVER_EID;
import static de.interactive_instruments.etf.testdriver.te.Types.TE_SUPPORTED_TEST_OBJECT_TYPES;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import de.interactive_instruments.CLUtils;
import de.interactive_instruments.Credentials;
import de.interactive_instruments.SUtils;
import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.component.ComponentInfo;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.DataStorageRegistry;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.IncompleteDtoException;
import de.interactive_instruments.etf.dal.dto.capabilities.ComponentDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.testdriver.*;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.properties.ConfigProperties;
import de.interactive_instruments.properties.ConfigPropertyHolder;

/**
 * TEAM-Engine test driver component
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
@ComponentInitializer(id = TE_TEST_DRIVER_EID)
public class TeTestDriver implements TestDriver {

	public static final String TE_TEST_DRIVER_EID = "07f42606-41b1-4871-a83b-64c20012f03b";
	public static final String TE_REMOTE_URL = "etf.testdrivers.teamengine.url";
	public static final String TE_REMOTE_USERNAME = "etf.testdrivers.teamengine.username";
	public static final String TE_REMOTE_PASSWORD = "etf.testdrivers.teamengine.password";
	final private ConfigProperties configProperties = new ConfigProperties();
	private TeTypeLoader typeLoader;
	private DataStorage dataStorageCallback;
	private URI apiUri;
	private Credentials credentials;
	private static final String supportedTeamEngineVersion = CLUtils.getManifestAttributeValue(TeTestDriver.class,
			"Test-Engine-Version");

	final static ComponentInfo COMPONENT_INFO = new ComponentInfo() {
		@Override
		public String getName() {
			return "SoapUI test driver";
		}

		@Override
		public EID getId() {
			return EidFactory.getDefault().createAndPreserveStr(TE_TEST_DRIVER_EID);
		}

		@Override
		public String getVersion() {
			return this.getClass().getPackage().getImplementationVersion();
		}

		@Override
		public String getVendor() {
			return this.getClass().getPackage().getImplementationVendor();
		}

		@Override
		public String getDescription() {
			return "Test driver for TEAM Engine " + supportedTeamEngineVersion;
		}
	};

	@Override
	public Collection<ExecutableTestSuiteDto> getExecutableTestSuites() {
		return typeLoader.getExecutableTestSuites();
	}

	@Override
	public Collection<TestObjectTypeDto> getTestObjectTypes() {
		return TE_SUPPORTED_TEST_OBJECT_TYPES.values();
	}

	@Override
	final public ComponentInfo getInfo() {
		return COMPONENT_INFO;
	}

	@Override
	public void lookupExecutableTestSuites(final EtsLookupRequest etsLookupRequest) {
		final Set<EID> etsIds = etsLookupRequest.getUnknownEts();
		final Set<ExecutableTestSuiteDto> knownEts = new HashSet<>();
		for (final EID etsId : etsIds) {
			final ExecutableTestSuiteDto ets = typeLoader.getExecutableTestSuiteById(etsId);
			if (ets != null) {
				knownEts.add(ets);
			}
		}
		etsLookupRequest.addKnownEts(knownEts);
	}

	@Override
	public TestTask createTestTask(final TestTaskDto testTaskDto) throws TestTaskInitializationException {
		try {
			Objects.requireNonNull(testTaskDto, "Test Task not set").ensureBasicValidity();

			// Get ETS
			testTaskDto.getTestObject().ensureBasicValidity();
			testTaskDto.getExecutableTestSuite().ensureBasicValidity();
			final TestTaskResultDto testTaskResult = new TestTaskResultDto();
			testTaskResult.setId(EidFactory.getDefault().createRandomId());
			testTaskDto.setTestTaskResult(testTaskResult);
			return new TeTestTask(apiUri, credentials, typeLoader, testTaskDto, dataStorageCallback);
		} catch (IncompleteDtoException e) {
			throw new TestTaskInitializationException(e);
		}

	}

	@Override
	public ConfigPropertyHolder getConfigurationProperties() {
		return this.configProperties;
	}

	@Override
	final public void init()
			throws ConfigurationException, IllegalStateException, InitializationException, InvalidStateTransitionException {
		configProperties.expectAllRequiredPropertiesSet();
		dataStorageCallback = DataStorageRegistry.instance().get(configProperties.getProperty(ETF_DATA_STORAGE_NAME));
		if (dataStorageCallback == null) {
			throw new InvalidStateTransitionException("Data Storage not set");
		}

		final String teUrl = configProperties.getPropertyOrDefault(TE_REMOTE_URL,
				"http://cite.opengeospatial.org/teamengine");
		if(SUtils.isNullOrEmpty(teUrl)) {
			throw new ConfigurationException("Property "+TE_REMOTE_URL+" not set");
		}
		try {
			if(teUrl.charAt(teUrl.length()-1)=='/') {
				apiUri =new URI(teUrl);
			}else{
				apiUri =new URI(teUrl+"/");
			}
		} catch (URISyntaxException e) {
			throw new ConfigurationException("Property "+TE_REMOTE_URL+" must be an URL");
		}

		if(configProperties.hasProperty(TE_REMOTE_USERNAME) && configProperties.hasProperty(TE_REMOTE_PASSWORD)) {
			credentials = new Credentials(configProperties.getProperty(TE_REMOTE_USERNAME), configProperties.getProperty(TE_REMOTE_PASSWORD));
		}else{
			credentials = null;
		}

		propagateComponents();

		typeLoader = new TeTypeLoader(dataStorageCallback, apiUri, credentials, this.getInfo());
		typeLoader.getConfigurationProperties().setPropertiesFrom(configProperties, true);
		typeLoader.init();
	}

	private void propagateComponents() throws InitializationException {
		// Propagate Component COMPONENT_INFO from here
		final WriteDao<ComponentDto> componentDao = ((WriteDao<ComponentDto>) dataStorageCallback.getDao(ComponentDto.class));
		try {
			try {
				componentDao.delete(this.getInfo().getId());
			} catch (ObjectWithIdNotFoundException e) {
				ExcUtils.suppress(e);
			}
			componentDao.add(new ComponentDto(this.getInfo()));
		} catch (StorageException e) {
			throw new InitializationException(e);
		}
	}

	@Override
	public boolean isInitialized() {
		return dataStorageCallback != null && typeLoader != null && typeLoader.isInitialized();
	}

	@Override
	public void release() {
		typeLoader.release();
	}
}
