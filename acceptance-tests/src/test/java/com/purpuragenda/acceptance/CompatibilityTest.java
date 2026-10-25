package com.purpuragenda.acceptance;

import org.junit.platform.suite.api.*;

@Suite @IncludeEngines("cucumber") @SelectClasspathResource("features/compatibility.feature")
@ConfigurationParameter(key="cucumber.glue",value="com.purpuragenda.acceptance.compat")
@ConfigurationParameter(key="cucumber.plugin",value="pretty,json:target/cucumber.json,junit:target/cucumber.xml")
public class CompatibilityTest {}
