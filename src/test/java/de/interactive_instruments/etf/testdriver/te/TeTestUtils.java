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

import static junit.framework.TestCase.assertTrue;

import java.io.IOException;

import de.interactive_instruments.IFile;
import de.interactive_instruments.etf.EtfConstants;
import de.interactive_instruments.etf.dal.dao.DataStorageRegistry;
import de.interactive_instruments.etf.dal.dao.basex.BsxDataStorage;
import de.interactive_instruments.exceptions.InitializationException;
import de.interactive_instruments.exceptions.InvalidStateTransitionException;
import de.interactive_instruments.exceptions.StorageException;
import de.interactive_instruments.exceptions.config.ConfigurationException;

/**
 * @author J. Herrmann ( herrmann <aT) interactive-instruments (doT> de )
 */
public class TeTestUtils {

	static IFile DATA_STORAGE_DIR;
	static BsxDataStorage DATA_STORAGE = new BsxDataStorage();

	static void ensureInitialization() throws ConfigurationException, InvalidStateTransitionException, InitializationException,
			StorageException, IOException {
		if (!DATA_STORAGE.isInitialized()) {
			if (System.getenv("ETF_DS_DIR") != null) {
				DATA_STORAGE_DIR = new IFile(System.getenv("ETF_DS_DIR"));
				DATA_STORAGE_DIR.mkdirs();
			} else if (new IFile("./build").exists()) {
				DATA_STORAGE_DIR = new IFile("./build/tmp/etf-ds");
				DATA_STORAGE_DIR.mkdirs();
			} else {
				DATA_STORAGE_DIR = null;
			}

			assertTrue(DATA_STORAGE_DIR != null && DATA_STORAGE_DIR.exists());
			DATA_STORAGE.getConfigurationProperties().setProperty(EtfConstants.ETF_DATASOURCE_DIR,
					DATA_STORAGE_DIR.getAbsolutePath());
			DATA_STORAGE.getConfigurationProperties().setProperty("etf.webapp.base.url", "http://localhost/etf-webapp");
			DATA_STORAGE.getConfigurationProperties().setProperty("etf.api.base.url", "http://localhost/etf-webapp/v2");
			DATA_STORAGE.init();
			DataStorageRegistry.instance().register(DATA_STORAGE);
		}
	}

}
