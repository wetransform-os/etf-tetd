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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Collections;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.interactive_instruments.*;
import de.interactive_instruments.etf.dal.dto.result.TestResultStatus;
import de.interactive_instruments.etf.dal.dto.run.TestTaskDto;
import de.interactive_instruments.etf.model.EidFactory;
import de.interactive_instruments.etf.testdriver.AbstractTestTask;
import de.interactive_instruments.etf.testdriver.ExecutableTestSuiteUnavailable;
import de.interactive_instruments.etf.testdriver.TestResultCollector;
import de.interactive_instruments.exceptions.*;
import de.interactive_instruments.exceptions.config.ConfigurationException;

/**
 * TEAM Engine test run task for executing test remotly on the OGC TEAM Engine.
 *
 * @author Jon Herrmann ( herrmann aT interactive-instruments doT de )
 */
class TeTestTask extends AbstractTestTask {

	private final int timeout;
	private final Credentials credentials;
	private final TeTypeLoader typeLoader;
	private final String etsSpecificPrefix;

	/**
	 * Default constructor.
	 *
	 * @throws IOException I/O error
	 */
	public TeTestTask(final int timeout, final Credentials credentials, final TeTypeLoader typeLoader,
			final TestTaskDto testTaskDto) {
		super(testTaskDto, new TeTestTaskProgress(), TeTestTask.class.getClassLoader());
		this.timeout = timeout;
		this.credentials = credentials;
		this.typeLoader = typeLoader;
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
		final String timeoutStr = TimeUtils.milisAsMinsSeconds(timeout);
		getLogger().info("Timeout is set to: " + timeoutStr);
		((TeTestTaskProgress) progress).stepCompleted();

		final Document result;
		try {
			result = builder.parse(UriUtils.openStream(new URI(apiUri), credentials, timeout));
		} catch (UriUtils.ConnectionException e) {
			getLogger().info("OGC TEAM Engine returned an error.");

			final String htmlErrorMessage = e.getErrorMessage();
			String errorMessage = null;
			if (htmlErrorMessage != null) {
				try {
					final org.jsoup.nodes.Document doc = Jsoup.parse(htmlErrorMessage);
					if (doc != null) {
						final Elements errors = doc.select("body p");
						if (errors != null) {
							final StringBuilder errorMessageBuilder = new StringBuilder();
							for (final org.jsoup.nodes.Element error : errors) {
								errorMessageBuilder.append(error.text()).append(SUtils.ENDL);
							}
							errorMessage = errorMessageBuilder.toString();
						}
					}
				} catch (Exception ign) {
					ExcUtils.suppress(ign);
				}
			}
			if (errorMessage != null) {
				getLogger().error("Error message: {}", errorMessage);
				reportError(
						"OGC TEAM Engine returned HTTP status code: "
								+ String.valueOf(e.getResponseMessage() + ". Message: " + errorMessage),
						htmlErrorMessage.getBytes(),
						"text/html");
			} else {
				getLogger().error("Response message: " + e.getResponseMessage());
				reportError(
						"OGC TEAM Engine returned an error: " +
								String.valueOf(e.getResponseMessage()),
						null, null);
			}
			throw e;
		} catch (final SocketTimeoutException e) {
			getLogger().info("The OGC TEAM Engine is taking too long to respond.");
			getLogger().info("Checking availability...");
			if (UriUtils.exists(new URI(
					testTaskDto.getExecutableTestSuite().getRemoteResource().toString()), credentials)) {
				getLogger().info("...[OK]. The OGC TEAM Engine is available. "
						+ "You may need to ask the system administrator to "
						+ "increase the OGC TEAM Engine test driver timeout.");
			} else {
				getLogger().info("...[FAILED]. The OGC TEAM Engine is not available. "
						+ "Try re-running the test after a few minutes.");
			}
			reportError(
					"OGC TEAM Engine is taking too long to respond. "
							+ "Timeout after " + timeoutStr + ".",
					null, null);
			throw e;
		}

		getLogger().info("Results received.");
		((TeTestTaskProgress) progress).stepCompleted();

		if (typeLoader.updateEtsFromResult(testTaskDto.getExecutableTestSuite(), result)) {
			getLogger().info("Internal ETS model updated.");
		}

		parseTestNgResult(result);
		((TeTestTaskProgress) progress).stepCompleted();
	}

	private void reportError(final String errorMesg, final byte[] data, final String mimeType)
			throws ObjectWithIdNotFoundException, StorageException {
		getCollector().internalError(errorMesg, data, mimeType);
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

		final TestResultCollector resultCollector = getCollector();

		final Node suiteResult = XmlUtils.getFirstChildNodeOfType(result, ELEMENT_NODE, "suite");
		resultCollector.startTestTask(testTaskDto.getExecutableTestSuite().getId().getId(), getStartTimestamp(suiteResult));

		// Save result document as attachment
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final Source xmlSource = new DOMSource(document);
		final Result outputTarget = new StreamResult(outputStream);
		TransformerFactory.newInstance().newTransformer().transform(xmlSource, outputTarget);
		final InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		resultCollector.saveAttachment(inputStream, "TEAM Engine result", "text/xml", "JunitXml");

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
				long testCaseEndTimeStamp = 0;
				boolean testStepResultCollected = false;
				boolean oneSkippedOrNotApplicableConfigStepRecorded = false;

				// Test Steps (no Test Assertions are used)
				for (Node testStep = firstTestStep; testStep != null; testStep = XmlUtils.getNextSiblingOfType(testStep,
						ELEMENT_NODE, "test-method")) {

					final long testStepEndTimestamp = getEndTimestamp(testStep);
					if (testCaseEndTimeStamp < testStepEndTimestamp) {
						testCaseEndTimeStamp = testStepEndTimestamp;
					}

					final int status = mapStatus(testStep);
					final boolean configStep = "true".equals(XmlUtils.getAttributeOrDefault(testStep, "is-config", "false"));

					// output only failed steps or only one skipped or not applicable config test step
					if (!configStep || status == 1 || (!oneSkippedOrNotApplicableConfigStepRecorded && status == 2 || status == 3)) {
						final long testStepStartTimestamp = getStartTimestamp(testStep);
						final String testStepId = getItemID(testStep);
						resultCollector.startTestStep(testStepId, testStepStartTimestamp);

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
									final String response = XmlUtils.nodeValue(attachment);
									resultCollector.saveAttachment(IOUtils.toInputStream(
											response, "UTF-8"), "Service Response",
											XmlUtils.isXml(response) ? "text/xml" : null,
											"ServiceResponse");
									break;
								case "request":
									final String request = XmlUtils.nodeValue(attachment);
									if (XmlUtils.isXml(request)) {
										resultCollector.saveAttachment(IOUtils.toInputStream(
												XmlUtils.nodeValue(attachment), "UTF-8"), "Request Parameter", "text/xml",
												"PostData");
									} else {
										resultCollector.saveAttachment(XmlUtils.nodeValue(attachment), "Request Parameter",
												"text/plain",
												"GetParameter");
									}
									break;
								default:
									resultCollector.saveAttachment(XmlUtils.nodeValue(attachment), type, null, type);
								}
							}
						}
						resultCollector.end(testStepId, status, testStepEndTimestamp);
						if(configStep && (status==2 || status==3)) {
							oneSkippedOrNotApplicableConfigStepRecorded=true;
						}
						testStepResultCollected = true;
					}
				}
				if (testStepResultCollected) {
					resultCollector.end(testCaseId, testCaseEndTimeStamp);
				} else {
					// only passed config steps collected,
					resultCollector.end(testCaseId, 0, testCaseEndTimeStamp);
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
			// if the failed test is a config step which has an AssertionError exception, it is just NOT APPLICABLE
			if ("true".equals(XmlUtils.getAttributeOrDefault(node, "is-config", "false"))) {
				final Node exception = XmlUtils.getFirstChildNodeOfType(node, ELEMENT_NODE, "exception");
				final String exceptionClass = XmlUtils.getAttribute(exception, "class");
				if (exceptionClass!=null && exceptionClass.equalsIgnoreCase("java.lang.AssertionError")) {
					// NOT APPLICABLE
					return 3;
				}
			}
			// FAILED
			return 1;
		case "SKIP":
			// if the skipped test has a SkipException, it is just NOT APPLICABLE
			final Node exception = XmlUtils.getFirstChildNodeOfType(node, ELEMENT_NODE, "exception");
			if (exception != null) {
				final String exceptionClass = XmlUtils.getAttribute(exception, "class");
				if (exceptionClass!=null && !exceptionClass.equalsIgnoreCase("org.testng.SkipException")) {
					// SKIPPED
					return 2;
				}
			}
			// NOT APPLICABLE
			return 3;
		}
		// UNDEFINED
		return 6;
	}

	private String getMessage(final Node node) {
		final Node exception = XmlUtils.getFirstChildNodeOfType(node, ELEMENT_NODE, "exception");
		if (exception != null) {
			final Node message = XmlUtils.getFirstChildNodeOfType(exception, ELEMENT_NODE, "message");
			if (message != null) {
				final String msg = XmlUtils.nodeValue(message);
				if (!SUtils.isNullOrEmpty(msg)) {
					return msg;
				}
			} else {
				final String exceptionClass = XmlUtils.getAttribute(exception, "class");
				if (!SUtils.isNullOrEmpty(exceptionClass)) {
					return "No message provided. Exception class " + exceptionClass;
				}
			}
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
