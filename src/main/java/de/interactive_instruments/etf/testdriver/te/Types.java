/**
 * Copyright 2017 European Union
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * This work was supported by the EU Interoperability Solutions for
 * European Public Administrations Programme (http://ec.europa.eu/isa)
 * through Action 1.17: A Reusable INSPIRE Reference Platform (ARE3NA).
 */
package de.interactive_instruments.etf.testdriver.te;

import de.interactive_instruments.etf.dal.dto.capabilities.TagDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.dal.dto.test.TestItemTypeDto;
import de.interactive_instruments.etf.detector.TestObjectTypeDetectorManager;
import de.interactive_instruments.etf.model.DefaultEidMap;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.model.EidMap;

/**
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class Types {

	private Types() {}

	// Supported Test Object Types
	public static final EidMap<TestObjectTypeDto> TE_SUPPORTED_TEST_OBJECT_TYPES = TestObjectTypeDetectorManager
			.getTypes(
					// WFS_2_0_TOT
					"9b6ef734-981e-4d60-aa81-d6730a1c6389");

	// Supported Test Item Types
	public static final EidMap<TestItemTypeDto> TE_TEST_ITEM_TYPES = new DefaultEidMap<TestItemTypeDto>() {
		{
			{
				final TestItemTypeDto testItemTypeDto = new TestItemTypeDto();
				testItemTypeDto.setLabel("TestNG Test Step");
				testItemTypeDto.setId(EidFactory.getDefault().createAndPreserveStr("b0469ab7-9d69-49ff-98a1-4c7960829b82"));
				testItemTypeDto.setDescription("TestNG Test Step");
				testItemTypeDto.setReference(
						"http://none");
				put(testItemTypeDto.getId(), testItemTypeDto);
			}

			{
				final TestItemTypeDto testItemTypeDto = new TestItemTypeDto();
				testItemTypeDto.setLabel("TestNG Test Assertion set");
				testItemTypeDto.setId(EidFactory.getDefault().createAndPreserveStr("161baae7-6c84-4bce-8185-3d3618a66011"));
				testItemTypeDto.setDescription(
						"Multiple TestNG assertions");
				testItemTypeDto.setReference(
						"http://none");
				put(testItemTypeDto.getId(), testItemTypeDto);
			}
		}
	};

	public static final TagDto TE_TEAM_ENGINE_TAG = createTeTag();

	private static TagDto createTeTag() {
		final TagDto tag = new TagDto();
		tag.setId(EidFactory.getDefault().createUUID("268af871-5cf0-443e-834a-ce40bed6c0e3"));
		tag.setPriority(1000);
		tag.setLabel("OGC Test Suites (remote execution)");
		tag.setDescription("Executable Test Suites that are executed on a remote OGC TEAM Engine instance");
		return tag;
	}
}
