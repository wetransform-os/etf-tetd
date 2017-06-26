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

import static de.interactive_instruments.etf.testdriver.te.Types.*;
import static org.w3c.dom.Node.ELEMENT_NODE;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import de.interactive_instruments.Credentials;
import de.interactive_instruments.SUtils;
import de.interactive_instruments.UriUtils;
import de.interactive_instruments.XmlUtils;
import de.interactive_instruments.etf.component.ComponentInfo;
import de.interactive_instruments.etf.dal.dao.Dao;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.Dto;
import de.interactive_instruments.etf.dal.dto.capabilities.ComponentDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TagDto;
import de.interactive_instruments.etf.dal.dto.test.*;
import de.interactive_instruments.etf.dal.dto.translation.LangTranslationTemplateCollectionDto;
import de.interactive_instruments.etf.dal.dto.translation.TranslationTemplateBundleDto;
import de.interactive_instruments.etf.dal.dto.translation.TranslationTemplateDto;
import de.interactive_instruments.etf.model.*;
import de.interactive_instruments.etf.testdriver.EtsTypeLoader;
import de.interactive_instruments.etf.testdriver.ExecutableTestSuiteLifeCycleListener;
import de.interactive_instruments.etf.testdriver.TypeBuildingFileVisitor;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.properties.ConfigProperties;
import de.interactive_instruments.properties.ConfigPropertyHolder;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class TeTypeLoader implements EtsTypeLoader {

	// Supported Test Object Types

	private final static String suitesPath = "rest/suites";
	private final static Set<String> whiteListEts = new HashSet<String>() {
		{
			add("WFS 2.0 (OGC 09-025r2/ISO 19142) Conformance Test Suite");
		}
	};

	private final ConfigProperties configProperties = new ConfigProperties();
	private final URI apiUri;
	private final Credentials credentials;
	private final ComponentInfo driverInfo;
	private final Logger logger = LoggerFactory.getLogger(TeTypeLoader.class);
	private final Dao<ExecutableTestSuiteDto> etsDao;
	private final DataStorage dataStorageCallback;
	private final static String NOTE = "<br/><br/> This Executable Test Suite is executed using a remote TEAM Engine instance hosted by OGC for "
			+ "their Compliance Program (CITE). The results are transformed into the ETF internal report format. Some information that is "
			+ "typically included in ETF test results is not included in the TEAM Engine reports and cannot be included in this test report."
			+ "<br/><br/>"
			+ "Please report any issues or problems with the OGC CITE tests in the "
			+ "<a target=\"_blank\" href=\"https://cite.opengeospatial.org/forum\">OGC Compliance Forum</a>.";

	public static final TranslationTemplateBundleDto TE_TRANSLATION_TEMPLATE_BUNDLE = createTranslationTemplateBundle();
	private boolean initialized = false;
	private final EidHolderMap<ExecutableTestSuiteDto> propagatedDtos = new DefaultEidHolderMap<>();
	private ExecutableTestSuiteLifeCycleListener mediator;

	private static TranslationTemplateBundleDto createTranslationTemplateBundle() {
		final TranslationTemplateBundleDto translationTemplateBundle = new TranslationTemplateBundleDto();
		translationTemplateBundle.setId(EidFactory.getDefault().createAndPreserveStr("62605758-f4ab-4ad8-9091-bde90467ecdd"));
		translationTemplateBundle.setSource(URI.create("library://etf-tetd"));
		final List<TranslationTemplateDto> translationTemplateDtos = new ArrayList<TranslationTemplateDto>() {
			{
				final TranslationTemplateDto template1En = new TranslationTemplateDto(
						"TR.teamEngineError", Locale.ENGLISH.toLanguageTag(),
						"OGC TEAM Engine reported a failed test: {error}");
				final TranslationTemplateDto template1De = new TranslationTemplateDto(
						"TR.teamEngineError", Locale.GERMAN.toLanguageTag(),
						"Die OGC TEAM Engine hat folgenden Fehler gemeldet: {error}");
				add(template1En);
				add(template1De);
			}
		};
		translationTemplateBundle.addTranslationTemplates(translationTemplateDtos);
		return translationTemplateBundle;
	}

	@Override
	public ExecutableTestSuiteDto getExecutableTestSuiteById(final EID eid) {
		return propagatedDtos.get(eid);
	}

	@Override
	public void setLifeCycleListener(final ExecutableTestSuiteLifeCycleListener mediator) {
		this.mediator = mediator;
	}

	private void addEts(final ExecutableTestSuiteDto ets) {
		propagatedDtos.add(ets);
		if (this.mediator != null) {
			this.mediator.lifeCycleChange(this, ExecutableTestSuiteLifeCycleListener.EventType.CREATED,
					DefaultEidHolderMap.singleton(ets));
		}
	}

	@Override
	public EidSet<? extends Dto> getTypes() {
		return propagatedDtos.toSet();
	}

	@Override
	public void release() {
		propagatedDtos.clear();
	}

	private static class TeTypeBuilder implements TypeBuildingFileVisitor.TypeBuilder<ExecutableTestSuiteDto> {

		@Override
		public TypeBuildingFileVisitor.TypeBuilderCmd<ExecutableTestSuiteDto> prepare(final Path path) {
			return null;
		}
	}

	/**
	 * Default constructor.
	 */
	public TeTypeLoader(final DataStorage dataStorageCallback, final URI apiUri,
			final Credentials credentials, final ComponentInfo driverInfo) {
		this.apiUri = apiUri;
		this.credentials = credentials;
		this.driverInfo = driverInfo;
		this.dataStorageCallback = dataStorageCallback;
		this.etsDao = dataStorageCallback.getDao(ExecutableTestSuiteDto.class);
	}

	@Override
	public void init() throws ConfigurationException, InitializationException, InvalidStateTransitionException {
		if (initialized == true) {
			throw new InvalidStateTransitionException("Already initialized");
		}

		this.configProperties.expectAllRequiredPropertiesSet();

		// First propagate static types
		final WriteDao<TestItemTypeDto> testItemTypeDao = ((WriteDao<TestItemTypeDto>) dataStorageCallback
				.getDao(TestItemTypeDto.class));
		try {
			testItemTypeDao.deleteAllExisting(TE_TEST_ITEM_TYPES.keySet());
			testItemTypeDao.addAll(TE_TEST_ITEM_TYPES.values());
		} catch (final StorageException e) {
			try {
				testItemTypeDao.deleteAllExisting(TE_TEST_ITEM_TYPES.keySet());
			} catch (StorageException ign) {
				ExcUtils.suppress(ign);
			}
			throw new InitializationException(e);
		}
		final WriteDao<TranslationTemplateBundleDto> translationTemplateBundleDao = ((WriteDao<TranslationTemplateBundleDto>) dataStorageCallback
				.getDao(TranslationTemplateBundleDto.class));
		try {
			translationTemplateBundleDao.deleteAllExisting(Collections.singleton(TE_TRANSLATION_TEMPLATE_BUNDLE.getId()));
			translationTemplateBundleDao.add(TE_TRANSLATION_TEMPLATE_BUNDLE);
		} catch (final StorageException e) {
			try {
				translationTemplateBundleDao.deleteAllExisting(Collections.singleton(TE_TRANSLATION_TEMPLATE_BUNDLE.getId()));
			} catch (StorageException ign) {
				ExcUtils.suppress(ign);
			}
			throw new InitializationException(e);
		}
		final WriteDao<TagDto> tagDao = ((WriteDao<TagDto>) dataStorageCallback
				.getDao(TagDto.class));
		try {
			tagDao.deleteAllExisting(Collections.singleton(TE_TEAM_ENGINE_TAG.getId()));
			tagDao.add(TE_TEAM_ENGINE_TAG);
		} catch (final StorageException e) {
			try {
				tagDao.deleteAllExisting(Collections.singleton(TE_TEAM_ENGINE_TAG.getId()));
			} catch (StorageException ign) {
				ExcUtils.suppress(ign);
			}
			throw new InitializationException(e);
		}

		final List<ExecutableTestSuiteDto> eTestSuitesToAdd = initEts();
		for (final ExecutableTestSuiteDto ets : eTestSuitesToAdd) {
			try {
				if (!etsDao.exists(ets.getId()) || etsDao.isDisabled(ets.getId())) {
					((WriteDao<ExecutableTestSuiteDto>) etsDao).add(ets);
				}
			} catch (StorageException e) {
				throw new InitializationException("Could not add/update ETS: ", e);
			}
		}

		this.initialized = true;
	}

	@Override
	public final boolean isInitialized() {
		return this.initialized;
	}

	private List<ExecutableTestSuiteDto> initEts() throws InitializationException {
		// Check if URL returns 404
		final URI suitesUri;
		try {
			suitesUri = new URI(apiUri.toString() + suitesPath);
		} catch (URISyntaxException e) {
			throw new InitializationException("Invalid URL", e);
		}
		if (!UriUtils.exists(suitesUri, credentials)) {
			throw new InitializationException("TEAM Engine application web interface not available at " + suitesUri.toString());
		}

		// TODO update mechanism:
		// get already installed ETS
		// - check if remoteURL is available
		// - compare label and version information with new fetched ETSs

		try {
			// Get list of Executable Test Suites
			final String etsOverview = UriUtils.loadAsString(suitesUri, credentials);
			final Document etsOverviewDoc = Jsoup.parse(etsOverview);
			final Elements etsUrls = etsOverviewDoc.select("body ul li a[href]");
			for (final Element etsUrl : etsUrls) {
				// Get single ETS
				final String etsDetails;
				final String etsUrlStr = UriUtils.getParent(suitesUri).toString() + etsUrl.attr("href");
				try {
					etsDetails = UriUtils.loadAsString(new URI(etsUrlStr), credentials);
				} catch (URISyntaxException e) {
					logger.error("Invalid URL retrieved", e);
					continue;
				}
				// Build pseudo ETS
				final Document etsDetailsDoc = Jsoup.parse(etsDetails);
				final String label = etsDetailsDoc.title();
				if (!whiteListEts.contains(label)) {
					logger.debug("Skipping non-whitelisted Executable Test Suite " + label);
					continue;
				} else {
					logger.debug("Adding Executable Test Suite " + label);
				}
				ExecutableTestSuiteDto ets = new ExecutableTestSuiteDto();
				ets.setLabel(label);
				ets.setReference(etsUrlStr);
				ets.setRemoteResource(URI.create(etsUrlStr));
				// The ETS ID is generated from the URL without the version
				final String etsUrlWithoutVersion = UriUtils.getParent(etsUrlStr);
				ets.setId(EidFactory.getDefault().createUUID(etsUrlWithoutVersion));
				ets.setVersionFromStr(UriUtils.lastSegment(etsUrlStr));
				// Check if an ETS already exists and if the version matches
				boolean create = true;
				if (etsDao.exists(ets.getId())) {
					try {
						final ExecutableTestSuiteDto etsComp = etsDao.getById(ets.getId()).getDto();
						if (etsComp.getVersion().equals(ets.getVersion())) {
							// version match, no update required. Check if the ETS is deactivated and reactivate it.
							ets = etsComp;
							etsComp.setDisabled(false);
							addEts(ets);
							create = false;
						}
					} catch (ObjectWithIdNotFoundException | StorageException e) {
						logger.error("Could not compare old ETS", e);
					}
				}
				if (create) {
					final Element descriptionEl = etsDetailsDoc.select("body p").first();
					final String description = descriptionEl != null ? descriptionEl.text()
							: "No description provided by OGC TEAM Engine";
					ets.setDescription(description + NOTE);
					ets.addTag(TE_TEAM_ENGINE_TAG);
					ets.setItemHash(SUtils.fastCalcHashAsHexStr(description));
					ets.setLastEditor("Open Geospatial Consortium");
					ets.setLastUpdateDateNow();
					ets.setTranslationTemplateBundle(TE_TRANSLATION_TEMPLATE_BUNDLE);
					ets.setTestDriver(new ComponentDto(driverInfo));
					ets.setSupportedTestObjectTypes(TE_SUPPORTED_TEST_OBJECT_TYPES.asList());
					addEts(ets);
				}
			}
			return propagatedDtos.asList();
		} catch (final IOException e) {
			throw new InitializationException("Could not retrieve Executable Test Suites with"
					+ " TEAM Engine application web interface ", e);
		}
	}

	boolean updateEtsFromResult(final ExecutableTestSuiteDto executableTestSuite, final org.w3c.dom.Document document)
			throws ParseException, ObjectWithIdNotFoundException, StorageException {

		// TODO parse ETS, compare hash, only update if nescessary

		final org.w3c.dom.Element result = document.getDocumentElement();
		if (!"testng-results".equals(result.getNodeName())) {
			throw new ParseException("Expected a TestNG result XML", document.getDocumentURI(), 0);
		}

		final Node testSuite = XmlUtils.getFirstChildNodeOfType(result, ELEMENT_NODE, "suite");
		final String etsSpecificPrefix = executableTestSuite.getId().getId() + executableTestSuite.getLabel();

		final LangTranslationTemplateCollectionDto defaultErr = TE_TRANSLATION_TEMPLATE_BUNDLE
				.getTranslationTemplateCollection("TR.teamEngineError");
		final TestItemTypeDto testNgAsseriton = TE_TEST_ITEM_TYPES.get("161baae7-6c84-4bce-8185-3d3618a66011");
		final TestItemTypeDto testNgStep = TE_TEST_ITEM_TYPES.get("b0469ab7-9d69-49ff-98a1-4c7960829b82");

		// Test Modules
		for (Node testModule = XmlUtils.getFirstChildNodeOfType(testSuite, ELEMENT_NODE,
				"test"); testModule != null; testModule = XmlUtils.getNextSiblingOfType(testModule, ELEMENT_NODE, "test")) {
			final EID testModuleId = getItemID(testModule, etsSpecificPrefix);
			final TestModuleDto testModuleDto = new TestModuleDto();
			testModuleDto.setId(testModuleId);
			setDefaultProperties(testModule, testModuleDto);
			testModuleDto.setParent(executableTestSuite);

			// Test Cases
			for (Node testCase = XmlUtils.getFirstChildNodeOfType(testModule, ELEMENT_NODE,
					"class"); testCase != null; testCase = XmlUtils.getNextSiblingOfType(testCase, ELEMENT_NODE, "class")) {
				final EID testCaseId = getItemID(testCase, etsSpecificPrefix);
				final TestCaseDto testCaseDto = new TestCaseDto();
				testCaseDto.setId(testCaseId);
				setDefaultProperties(testCase, testCaseDto);
				testCaseDto.setParent(testModuleDto);

				// Test Steps
				for (Node testStep = XmlUtils.getFirstChildNodeOfType(testCase, ELEMENT_NODE,
						"test-method"); testStep != null; testStep = XmlUtils.getNextSiblingOfType(testStep, ELEMENT_NODE,
								"test-method")) {
					final EID testStepId = getItemID(testStep, etsSpecificPrefix);
					final TestStepDto testStepDto = new TestStepDto();
					testStepDto.setId(testStepId);
					setDefaultProperties(testStep, testStepDto);
					testStepDto.setParent(testCaseDto);
					testStepDto.setType(testNgStep);
					testStepDto.setStatementForExecution("NOT_APPLICABLE");

					// Pseudo Assertion
					/*
					final EID assertionId = getAssertionID(testStep, etsSpecificPrefix);
					final TestAssertionDto assertionDto = new TestAssertionDto();
					assertionDto.setId(assertionId);
					setDefaultProperties(testStep, assertionDto);
					assertionDto.setParent(testStepDto);
					assertionDto.addTranslationTemplate(defaultErr);
					assertionDto.setType(testNgAsseriton);
					assertionDto.setExpectedResult("NOT_APPLICABLE");
					assertionDto.setExpression("NOT_APPLICABLE");
					testStepDto.addTestAssertion(assertionDto);
					*/
					testCaseDto.addTestStep(testStepDto);
				}
				testModuleDto.addTestCase(testCaseDto);

			}
			executableTestSuite.addTestModule(testModuleDto);
		}
		propagatedDtos.add(executableTestSuite);
		((WriteDao) etsDao).replace(executableTestSuite);
		return true;
	}

	private EID getAssertionID(final Node node, final String etsSpecificPrefix) {
		return EidFactory.getDefault().createUUID(
				etsSpecificPrefix +
						XmlUtils.getAttribute(node.getParentNode(), "name") +
						XmlUtils.getAttribute(node, "name") + "Assertion");
	}

	private EID getItemID(final Node node, final String etsSpecificPrefix) {
		return EidFactory.getDefault().createUUID(
				etsSpecificPrefix +
						XmlUtils.getAttribute(node.getParentNode(), "name") +
						XmlUtils.getAttribute(node, "name"));
	}

	private void setDefaultProperties(final Node node, final TestModelItemDto dto) {
		dto.setLabel(XmlUtils.getAttribute(node, "name"));
		dto.setDescription(XmlUtils.getAttribute(node, "description"));
	}

	@Override
	public ConfigPropertyHolder getConfigurationProperties() {
		return configProperties;
	}

}
