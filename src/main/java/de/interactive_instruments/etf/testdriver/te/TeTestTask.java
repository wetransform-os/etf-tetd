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

import static org.w3c.dom.Node.ELEMENT_NODE;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.interactive_instruments.*;
import de.interactive_instruments.etf.dal.dao.DataStorage;
import de.interactive_instruments.etf.dal.dto.Dto;
import de.interactive_instruments.etf.dal.dto.result.TestTaskResultDto;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.testdriver.AbstractTestTask;
import de.interactive_instruments.etf.testdriver.ExecutableTestSuiteUnavailable;
import de.interactive_instruments.exceptions.InitializationException;
import de.interactive_instruments.exceptions.InvalidStateTransitionException;
import de.interactive_instruments.exceptions.ParseException;
import de.interactive_instruments.exceptions.config.ConfigurationException;

/**
 * TEAM Engine test run task for executing test remotly on the OGC TEAM Engine.
 *
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
class TeTestTask<T extends Dto> extends AbstractTestTask {

	private final int timeout;
	private final Credentials credentials;
	private final TeTypeLoader typeLoader;
	private final DataStorage dataStorageCallback;
	private final String etsSpecificPrefix;

	/**
	 * Default constructor.
	 *
	 * @throws IOException I/O error
	 */
	public TeTestTask(final int timeout, final Credentials credentials, final TeTypeLoader typeLoader,
			final TestTaskDto testTaskDto, final DataStorage dataStorageCallback) {
		super(testTaskDto, new TeTestTaskProgress(), TeTestTask.class.getClassLoader());
		this.timeout = timeout;
		this.credentials = credentials;
		this.typeLoader = typeLoader;
		this.dataStorageCallback = dataStorageCallback;
		etsSpecificPrefix = testTaskDto.getExecutableTestSuite().getId().getId() +
				testTaskDto.getExecutableTestSuite().getLabel();
	}

	@Override
	protected void doRun() throws Exception {
		final DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
		domFactory.setNamespaceAware(true);
		final DocumentBuilder builder = domFactory.newDocumentBuilder();

		final String endpoint = this.testTaskDto.getTestObject().getResourceByName(
				"serviceEndpoint").toString();

		final String apiUri = UriUtils.withQueryParameters(
				testTaskDto.getExecutableTestSuite().getRemoteResource().toString() + "run",
				Collections.singletonMap("wfs",
						endpoint.replace("&", "%26")));
		getLogger().info("Invoking TEAM Engine remotely. This may take a while. "
				+ "Progress messages are not supported.");
		getLogger().info("Timeout is set to: " + TimeUtils.milisAsMinsSeconds(timeout));
		((TeTestTaskProgress) progress).stepCompleted();

		final Document result;
		try {
			result = builder.parse(UriUtils.openStream(new URI(apiUri), credentials, timeout));
		} catch (UriUtils.ServerException e) {
			if (e.getResponseMessage() != null) {
				getLogger().info("OGC TEAM Engine returned an error: '" + e.getResponseMessage() + "'");
			}
			throw e;
		}
		getLogger().info("Results received.");
		((TeTestTaskProgress) progress).stepCompleted();

		if (typeLoader.updateEtsFromResult(testTaskDto.getExecutableTestSuite(), result)) {
			getLogger().info("Internal ETS model updated.");
		}

		parseTestNgResult(result);
		((TeTestTaskProgress) progress).stepCompleted();

		testTaskDto.setTestTaskResult(
				dataStorageCallback.getDao(TestTaskResultDto.class).getById(
						EidFactory.getDefault().createAndPreserveStr(resultCollector.getTestTaskResultId())).getDto());
	}

	private void parseTestNgResult(final Document document) throws Exception {
		getLogger().info("Transforming results.");
		final Element result = document.getDocumentElement();
		if (!"testng-results".equals(result.getNodeName())) {
			throw new ParseException("Expected a TestNG result XML", document.getDocumentURI(), 0);
		}

		final String failedAssertions = XmlUtils.getAttribute(result, "failed");
		final String passedAssertions = XmlUtils.getAttribute(result, "passed");
		final Integer passedAssertionsInt = Integer.valueOf(passedAssertions);
		getLogger().info("{} of {} assertions passed", passedAssertionsInt,
				Integer.valueOf(passedAssertions + Integer.valueOf(failedAssertions)));

		final Node suiteResult = XmlUtils.getFirstChildNodeOfType(result, ELEMENT_NODE, "suite");
		resultCollector.startTestTask(testTaskDto.getExecutableTestSuite().getId().getId(), getStartTimestamp(suiteResult));

		// Test Modules
		for (Node testModule = XmlUtils.getFirstChildNodeOfType(suiteResult, ELEMENT_NODE,
				"test"); testModule != null; testModule = XmlUtils.getNextSiblingOfType(testModule, ELEMENT_NODE, "test")) {
			final String testModuleId = getItemID(testModule);
			resultCollector.startTestModule(testModuleId, getStartTimestamp(testModule));

			// Test Cases
			for (Node testCase = XmlUtils.getFirstChildNodeOfType(testModule, ELEMENT_NODE,
					"class"); testCase != null; testCase = XmlUtils.getNextSiblingOfType(testCase, ELEMENT_NODE, "class")) {
				final String testCaseId = getItemID(testCase);

				// Get start timestamp from first test step
				final Node firstTestStep = XmlUtils.getFirstChildNodeOfType(testCase, ELEMENT_NODE, "test-method");
				if (firstTestStep != null) {
					resultCollector.startTestCase(testCaseId, getStartTimestamp(firstTestStep));
				} else {
					resultCollector.startTestCase(testCaseId);
				}
				Node lastTestStep = null;

				// Test Steps
				for (Node testStep = firstTestStep; testStep != null; testStep = XmlUtils.getNextSiblingOfType(testStep,
						ELEMENT_NODE, "test-method")) {

					final long testStepStartTimestamp = getStartTimestamp(testStep);
					final String testStepId = getItemID(testStep);
					resultCollector.startTestStep(testStepId, testStepStartTimestamp);
					// Pseudo Assertion
					final String assertionId = getAssertionID(testStep);
					resultCollector.startTestAssertion(assertionId, testStepStartTimestamp);

					final String message = getMessage(testStep);
					if (!SUtils.isNullOrEmpty(message)) {
						resultCollector.addMessage("TR.teamEngineError", "error", message);
					}

					// Attachments
					for (Node attachments = XmlUtils.getFirstChildNodeOfType(testStep, ELEMENT_NODE,
							"attributes"); attachments != null; attachments = XmlUtils.getNextSiblingOfType(attachments,
									ELEMENT_NODE, "attributes")) {
						for (Node attachment = XmlUtils.getFirstChildNodeOfType(attachments, ELEMENT_NODE,
								"attribute"); attachment != null; attachment = XmlUtils.getNextSiblingOfType(attachment,
										ELEMENT_NODE, "attribute")) {
							final String type = XmlUtils.getAttribute(attachment, "name");
							switch (type) {
							case "response":
								resultCollector.saveAttachment(XmlUtils.nodeValue(attachment), "Service Response", null,
										"ServiceResponse");
								break;
							case "request":
								final String request = XmlUtils.nodeValue(attachment);
								if (XmlUtils.isXml(request)) {
									resultCollector.saveAttachment(IOUtils.toInputStream(
											XmlUtils.nodeValue(attachment), "UTF-8"), "Request Parameter", null,
											"PostParameter");
								} else {
									resultCollector.saveAttachment(XmlUtils.nodeValue(attachment), "Request Parameter", null,
											"GetParameter");
								}
								break;
							default:
								resultCollector.saveAttachment(XmlUtils.nodeValue(attachment), type, null, type);
							}
						}
					}
					final long assertionEndTimestamp = getEndTimestamp(testStep);
					resultCollector.end(assertionId, mapStatus(testStep), assertionEndTimestamp);
					resultCollector.end(testStepId, assertionEndTimestamp);
					lastTestStep = testStep;
				}
				if (lastTestStep != null) {
					// Get end timestamp from last test step
					resultCollector.end(testCaseId, getEndTimestamp(lastTestStep));
				} else {
					resultCollector.end(testCaseId);
				}
			}
			resultCollector.end(testModuleId, getEndTimestamp(testModule));
		}
		resultCollector.end(testTaskDto.getId().getId(), getEndTimestamp(suiteResult));
	}

	private String getAssertionID(final Node node) {
		return EidFactory.getDefault().createUUID(
				etsSpecificPrefix +
						XmlUtils.getAttribute(node.getParentNode(), "name") +
						XmlUtils.getAttribute(node, "name") + "Assertion")
				.getId();
	}

	private String getItemID(final Node node) {
		return EidFactory.getDefault().createUUID(
				etsSpecificPrefix +
						XmlUtils.getAttribute(node.getParentNode(), "name") +
						XmlUtils.getAttribute(node, "name"))
				.getId();
	}

	private long getStartTimestamp(final Node node) {
		return TimeUtils.string8601ToDate(XmlUtils.getAttribute(node,
				"started-at")).getTime();
	}

	private long getEndTimestamp(final Node node) {
		return TimeUtils.string8601ToDate(XmlUtils.getAttribute(node,
				"finished-at")).getTime();
	}

	private int mapStatus(final Node node) {
		final String status = XmlUtils.getAttribute(node, "status");
		switch (status) {
		case "PASS":
			return 0;
		case "FAIL":
			return 1;
		case "SKIP":
			return 2;
		}
		// UNDEFINED
		return 6;
	}

	private String getMessage(final Node node) {
		final Node exception = XmlUtils.getFirstChildNodeOfType(node, ELEMENT_NODE, "exception");
		if (exception != null) {
			final Node message = XmlUtils.getFirstChildNodeOfType(exception, ELEMENT_NODE, "message");
			return XmlUtils.nodeValue(message);
		}
		return null;
	}

	@Override
	protected void doInit() throws ConfigurationException, InitializationException {
		try {
			// nothing to init
		} catch (Exception e) {
			throw new ExecutableTestSuiteUnavailable(testTaskDto.getExecutableTestSuite(), e);
		}
	}

	@Override
	public void doRelease() {
		// nothing to release
	}

	@Override
	protected void doCancel() throws InvalidStateTransitionException {
		// no background threads
	}
}
