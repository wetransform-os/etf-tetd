# ETF Test Driver for running running TEAM-Engine-based test projects

The test driver is loaded by the ETF framework at runtime. The test driver
executes TEAM-Engine-based test projects on a remote TEAM Engine instance, for example, the OGC CITE tests.

[![European Union Public Licence 1.2](https://img.shields.io/badge/license-EUPL%201.2-blue.svg)](https://joinup.ec.europa.eu/software/page/eupl)

&copy; 2017 European Union. Licensed under the EUPL.

## About ETF

ETF is an open source testing framework for validating spatial data, metadata and web services in Spatial Data Infrastructures (SDIs). For documentation about ETF, see [http://docs.etf-validator.net](http://docs.etf-validator.net/).

Please report issues [in the GitHub issue tracker of the ETF Web Application](https://github.com/interactive-instruments/etf-webapp/issues).

ETF component version numbers comply with the [Semantic Versioning Specification 2.0.0](http://semver.org/spec/v2.0.0.html).

## Build information

The project can be build and installed by running the gradlew.sh/.bat wrapper with:
```gradle
$ gradlew build install
```

## Installation
Copy the JAR path to the _$driver_ directory. The $driver directory is configured in your _etf-config.properties_ configuration path as variable _etf.testdrivers.dir_. If the driver is loaded correctly, it is displayed on the status page.

## Updating
Remove the old JAR path from the _$driver_ directory and exchange it with the new version.
