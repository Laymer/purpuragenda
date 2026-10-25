package com.purpuragenda.acceptance;
import org.junit.platform.suite.api.*;
@Suite @IncludeEngines("cucumber") @SelectClasspathResource("features/api.feature")
@ConfigurationParameter(key="cucumber.glue",value="com.purpuragenda.acceptance.api")
@ConfigurationParameter(key="cucumber.plugin",value="pretty,json:target/api-cucumber.json,junit:target/api-cucumber.xml")
public class ApiAcceptanceIT {}
