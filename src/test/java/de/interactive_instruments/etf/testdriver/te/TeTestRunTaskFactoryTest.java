/**
 * Copyright 2017-2019 European Union
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

import static de.interactive_instruments.etf.dal.dto.result.TestResultStatus.INTERNAL_ERROR;
import static de.interactive_instruments.etf.testdriver.te.TeTestDriver.TE_TEST_DRIVER_EID;
import static de.interactive_instruments.etf.testdriver.te.TeTestDriver.TE_TIMEOUT_SEC;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import de.interactive_instruments.IFile;
import de.interactive_instruments.SUtils;
import de.interactive_instruments.XmlUtils;
import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.component.ComponentNotLoadedException;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dao.DataStorageRegistry;
import de.interactive_instruments.etf.dal.dao.WriteDao;
import de.interactive_instruments.etf.dal.dto.capabilities.ResourceDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectDto;
import de.interactive_instruments.etf.dal.dto.capabilities.TestObjectTypeDto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestRunDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.dal.dto.test.ExecutableTestSuiteDto;
import de.interactive_instruments.etf.model.EID;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.test.DataStorageTestUtils;
import de.interactive_instruments.etf.testdriver.*;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;
import de.interactive_instruments.properties.PropertyUtils;

/**
 *
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TeTestRunTaskFactoryTest {

    // DO NOT RUN THE TESTS IN THE IDE BUT WITH GRADLE

    private static TestDriverManager testDriverManager = null;
    private static DataStorage DATA_STORAGE = DataStorageTestUtils.inMemoryStorage();

    private final static String VERSION = "1.26";
    private final static String LABEL = "WFS 2.0 (OGC 09-025r2/ISO 19142) Conformance Test Suite";
    private final static EID wfs20EtsId = EidFactory.getDefault().createUUID(
            "http://cite.opengeospatial.org/teamengine/rest/suites/wfs20/");

    private static WriteDao<ExecutableTestSuiteDto> etsDao() {
        return ((WriteDao) DATA_STORAGE.getDao(ExecutableTestSuiteDto.class));
    }

    private TestRunDto createTestRunDtoForProject(final String url)
            throws ComponentNotLoadedException, ConfigurationException, URISyntaxException,
            StorageException, ObjectWithIdNotFoundException, IOException {

        final TestObjectDto testObjectDto = new TestObjectDto();
        testObjectDto.setId(
                EidFactory.getDefault().createAndPreserveStr("fcfe9677-7b77-41dd-a17c-56884f60824f"));
        testObjectDto.setLabel("Cite 2013 WFS");
        final TestObjectTypeDto wfsTestObjectType = DATA_STORAGE.getDao(TestObjectTypeDto.class).getById(
                EidFactory.getDefault().createAndPreserveStr("9b6ef734-981e-4d60-aa81-d6730a1c6389")).getDto();
        testObjectDto.setTestObjectType(wfsTestObjectType);
        testObjectDto.addResource(new ResourceDto("serviceEndpoint", url));
        testObjectDto.setDescription("none");
        testObjectDto.setVersionFromStr("1.0.0");
        testObjectDto.setCreationDate(new Date(0));
        testObjectDto.setAuthor("ii");
        testObjectDto.setRemoteResource(URI.create("http://none"));
        testObjectDto.setItemHash("");
        testObjectDto.setLocalPath("/none");

        ((WriteDao) DATA_STORAGE.getDao(TestObjectDto.class)).deleteAllExisting(Collections.singleton(testObjectDto.getId()));
        ((WriteDao) DATA_STORAGE.getDao(TestObjectDto.class)).add(testObjectDto);

        final ExecutableTestSuiteDto ets = DATA_STORAGE.getDao(ExecutableTestSuiteDto.class).getById(wfs20EtsId).getDto();

        final TestTaskDto testTaskDto = new TestTaskDto();
        testTaskDto.setId(EidFactory.getDefault().createAndPreserveStr("aa03825a-2f64-4e52-bdba-90a08adb80ce"));
        testTaskDto.setExecutableTestSuite(ets);
        testTaskDto.setTestObject(testObjectDto);

        final TestRunDto testRunDto = new TestRunDto();
        testRunDto.setDefaultLang("en");
        testRunDto.setId(EidFactory.getDefault().createRandomId());
        testRunDto.setLabel("Run label");
        testRunDto.setStartTimestamp(new Date(0));
        testRunDto.addTestTask(testTaskDto);

        try {
            ((WriteDao) DATA_STORAGE.getDao(TestRunDto.class)).deleteAllExisting(Collections.singleton(testRunDto.getId()));
        } catch (Exception e) {
            ExcUtils.suppress(e);
        }
        return testRunDto;
    }

    @BeforeClass
    public static void setUp()
            throws IOException, ConfigurationException, InvalidStateTransitionException,
            InitializationException, ObjectWithIdNotFoundException, StorageException {

        // DO NOT RUN THE TESTS IN THE IDE BUT WITH GRADLE

        // Init logger
        LoggerFactory.getLogger(TeTestRunTaskFactoryTest.class).info("Started");

        if (DataStorageRegistry.instance().get(DATA_STORAGE.getClass().getName()) == null) {
            DataStorageRegistry.instance().register(DATA_STORAGE);
        }

        if (testDriverManager == null) {

            // Delete old ETS
            try {
                etsDao().delete(wfs20EtsId);
            } catch (final ObjectWithIdNotFoundException e) {
                ExcUtils.suppress(e);
            }

            final IFile tdDir = new IFile(PropertyUtils.getenvOrProperty(
                    "ETF_TD_DEPLOYMENT_DIR", "./build/tmp/td"));
            tdDir.ensureDir();
            tdDir.expectDirIsReadable();

            // Load driver
            testDriverManager = new DefaultTestDriverManager();
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_TESTDRIVERS_DIR, tdDir.getAbsolutePath());
            final IFile attachmentDir = new IFile(PropertyUtils.getenvOrProperty(
                    "ETF_DS_DIR", "./build/tmp/etf-ds")).secureExpandPathDown("attachments");
            attachmentDir.deleteDirectory();
            attachmentDir.mkdirs();
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_ATTACHMENT_DIR, attachmentDir.getAbsolutePath());
            testDriverManager.getConfigurationProperties().setProperty(
                    EtfConstants.ETF_DATA_STORAGE_NAME,
                    DATA_STORAGE.getClass().getName());

            testDriverManager.init();
            testDriverManager.load(EidFactory.getDefault().createAndPreserveStr(TE_TEST_DRIVER_EID));
        }

    }

    @Test
    public void T1_checkInitializedEts() throws Exception, ComponentNotLoadedException {
        assertTrue(etsDao().available(wfs20EtsId));

        final ExecutableTestSuiteDto ets = etsDao().getById(wfs20EtsId).getDto();
        assertEquals(LABEL, ets.getLabel());
        assertEquals(VERSION + ".0", ets.getVersionAsStr());
        // Clean non-initialized ETS
        assertEquals(0, ets.getLowestLevelItemSize());
    }

    @Test
    public void T2_parseTestNgResults() throws Exception, ComponentNotLoadedException {
        // Depends on testClasses (Classloader is not the test class loader so getResource does not work here)
        final File file = new File("build/resources/test/response.xml");

        final DocumentBuilderFactory domFactory = XmlUtils.newDocumentBuilderFactoryInstance();
        domFactory.setNamespaceAware(true);
        final DocumentBuilder builder = domFactory.newDocumentBuilder();
        final Document result = builder.parse(file);

        final String testUrl = "https://services.interactive-instruments.de/cite-xs-46/simpledemo/cgi-bin/cities-postgresql/wfs?request=GetCapabilities&service=wfs";
        final TestRunDto testRunDto = createTestRunDtoForProject(testUrl);
        final TestRun testRun = testDriverManager.createTestRun(testRunDto);
        final TestTask task = testRun.getTestTasks().get(0);

        final Method method = task.getClass().getDeclaredMethod("parseTestNgResult", Document.class);
        method.setAccessible(true);
        method.invoke(task, result);
    }

    @Test
    public void T3_runTest() throws Exception, ComponentNotLoadedException {

        final String testUrl = "https://services.interactive-instruments.de/cite-xs-46/simpledemo/cgi-bin/cities-postgresql/wfs?request=GetCapabilities&service=wfs";
        TestRunDto testRunDto = createTestRunDtoForProject(testUrl);

        final TestRun testRun = testDriverManager.createTestRun(testRunDto);
        final TaskPoolRegistry<TestRunDto, TestRun> taskPoolRegistry = new TaskPoolRegistry<>(1, 1);
        testRun.init();
        taskPoolRegistry.submitTask(testRun);
        final TestRunDto runResult = taskPoolRegistry.getTaskById(testRunDto.getId()).waitForResult();

        assertNotNull(runResult);
        assertNotNull(runResult.getTestTaskResults());
        assertFalse(runResult.getTestTaskResults().isEmpty());
    }

    @Test
    public void T4_runTestInvalidUrl() throws Exception, ComponentNotLoadedException {
        final String testUrl = "http://example.com";
        TestRunDto testRunDto = createTestRunDtoForProject(testUrl);

        final TestRun testRun = testDriverManager.createTestRun(testRunDto);
        final TaskPoolRegistry<TestRunDto, TestRun> taskPoolRegistry = new TaskPoolRegistry<>(1, 1);
        testRun.init();
        taskPoolRegistry.submitTask(testRun);

        final TestRunDto runResult = taskPoolRegistry.getTaskById(testRunDto.getId()).waitForResult();
        assertNotNull(runResult);
        final TestTaskResultDto result = runResult.getTestTasks().get(0).getTestTaskResult();
        assertEquals(INTERNAL_ERROR, result.getResultStatus());
        assertFalse(SUtils.isNullOrEmpty(result.getErrorMessage()));
        assertTrue(result.getErrorMessage().contains("OGC TEAM Engine returned HTTP status code"));
        // Todo not working with inmemory resultcollector
        // assertEquals(2, result.getAttachments().size());
        assertTrue(result.getTestModuleResults() == null || result.getTestModuleResults().isEmpty());
    }

    // @Test(timeout = 90)
    @Test
    public void T5_timeoutTest() throws Exception, ComponentNotLoadedException {

        final String testUrl = "https://services.interactive-instruments.de/cite-xs-46/simpledemo/cgi-bin/cities-postgresql/wfs?request=GetCapabilities&service=wfs";
        final TestRunDto testRunDto = createTestRunDtoForProject(testUrl);

        final String timeout = "10";

        testDriverManager.release();
        testDriverManager.getConfigurationProperties().setProperty(TE_TIMEOUT_SEC, timeout);
        assertEquals(timeout, testDriverManager.getConfigurationProperties().getProperty(TE_TIMEOUT_SEC));
        testDriverManager.init();
        testDriverManager.load(EidFactory.getDefault().createAndPreserveStr(TE_TEST_DRIVER_EID));

        final TestRun testRun = testDriverManager.createTestRun(testRunDto);
        final TaskPoolRegistry<TestRunDto, TestRun> taskPoolRegistry = new TaskPoolRegistry<>(1, 1);
        testRun.init();
        taskPoolRegistry.submitTask(testRun);

        final TestRunDto runResult = taskPoolRegistry.getTaskById(testRunDto.getId()).waitForResult();
        assertNotNull(runResult);
        final TestTaskResultDto result = runResult.getTestTasks().get(0).getTestTaskResult();
        assertEquals(INTERNAL_ERROR, result.getResultStatus());
        assertFalse(SUtils.isNullOrEmpty(result.getErrorMessage()));
        assertTrue(
                result.getErrorMessage().contains("OGC TEAM Engine is taking too long to respond. Timeout after " + timeout));
        assertEquals(1, result.getAttachments().size());
        assertTrue(result.getTestModuleResults() == null || result.getTestModuleResults().isEmpty());
    }

}
