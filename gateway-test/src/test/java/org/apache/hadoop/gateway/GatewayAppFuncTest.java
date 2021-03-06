/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.hadoop.gateway.security.ldap.SimpleLdapDirectoryServer;
import org.apache.hadoop.gateway.services.DefaultGatewayServices;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.ServiceLifecycleException;
import org.apache.hadoop.gateway.services.topology.TopologyService;
import org.apache.hadoop.test.TestUtils;
import org.apache.hadoop.test.mock.MockServer;
import org.apache.http.HttpStatus;
import org.apache.log4j.Appender;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.jayway.restassured.RestAssured.given;
import static org.apache.hadoop.test.TestUtils.LOG_ENTER;
import static org.apache.hadoop.test.TestUtils.LOG_EXIT;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;
import static org.xmlmatchers.transform.XmlConverters.the;
import static org.xmlmatchers.xpath.HasXPath.hasXPath;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

public class GatewayAppFuncTest {

  private static Logger LOG = LoggerFactory.getLogger( GatewayAppFuncTest.class );
  private static Class DAT = GatewayAppFuncTest.class;

  private static Enumeration<Appender> appenders;
  private static GatewayTestConfig config;
  private static DefaultGatewayServices services;
  private static GatewayServer gateway;
  private static int gatewayPort;
  private static String gatewayUrl;
  private static String clusterUrl;
  private static SimpleLdapDirectoryServer ldap;
  private static TcpTransport ldapTransport;
  private static int ldapPort;
  private static Properties params;
  private static TopologyService topos;
  private static MockServer mockWebHdfs;

  @BeforeClass
  public static void setupSuite() throws Exception {
    LOG_ENTER();
    //appenders = NoOpAppender.setUp();
    setupLdap();
    setupGateway();
    LOG_EXIT();
  }

  @AfterClass
  public static void cleanupSuite() throws Exception {
    LOG_ENTER();
    gateway.stop();
    ldap.stop( true );
    FileUtils.deleteQuietly( new File( config.getGatewayHomeDir() ) );
    //NoOpAppender.tearDown( appenders );
    LOG_EXIT();
  }

  @After
  public void cleanupTest() throws Exception {
    FileUtils.cleanDirectory( new File( config.getGatewayTopologyDir() ) );
    FileUtils.cleanDirectory( new File( config.getGatewayDeploymentDir() ) );
  }

  public static void setupLdap() throws Exception {
    String basedir = System.getProperty("basedir");
    if (basedir == null) {
      basedir = new File(".").getCanonicalPath();
    }
    Path path = FileSystems.getDefault().getPath(basedir, "/src/test/resources/users.ldif");

    ldapTransport = new TcpTransport( 0 );
    ldap = new SimpleLdapDirectoryServer( "dc=hadoop,dc=apache,dc=org", path.toFile(), ldapTransport );
    ldap.start();
    ldapPort = ldapTransport.getAcceptor().getLocalAddress().getPort();
    LOG.info( "LDAP port = " + ldapPort );
  }

  public static void setupGateway() throws Exception {

    File targetDir = new File( System.getProperty( "user.dir" ), "target" );
    File gatewayDir = new File( targetDir, "gateway-home-" + UUID.randomUUID() );
    gatewayDir.mkdirs();

    config = new GatewayTestConfig();
    config.setGatewayHomeDir( gatewayDir.getAbsolutePath() );

    URL svcsFileUrl = TestUtils.getResourceUrl( DAT, "test-svcs/readme.txt" );
    File svcsFile = new File( svcsFileUrl.getFile() );
    File svcsDir = svcsFile.getParentFile();
    config.setGatewayServicesDir( svcsDir.getAbsolutePath() );

    URL appsFileUrl = TestUtils.getResourceUrl( DAT, "test-apps/readme.txt" );
    File appsFile = new File( appsFileUrl.getFile() );
    File appsDir = appsFile.getParentFile();
    config.setGatewayApplicationsDir( appsDir.getAbsolutePath() );

    File topoDir = new File( config.getGatewayTopologyDir() );
    topoDir.mkdirs();

    File deployDir = new File( config.getGatewayDeploymentDir() );
    deployDir.mkdirs();


    setupMockServers();
    startGatewayServer();
  }

  public static void setupMockServers() throws Exception {
    mockWebHdfs = new MockServer( "WEBHDFS", true );
  }

  public static void startGatewayServer() throws Exception {
    services = new DefaultGatewayServices();
    Map<String,String> options = new HashMap<>();
    options.put( "persist-master", "false" );
    options.put( "master", "password" );
    try {
      services.init( config, options );
    } catch ( ServiceLifecycleException e ) {
      e.printStackTrace(); // I18N not required.
    }
    topos = services.getService(GatewayServices.TOPOLOGY_SERVICE);

    gateway = GatewayServer.startGateway( config, services );
    MatcherAssert.assertThat( "Failed to start gateway.", gateway, notNullValue() );

    gatewayPort = gateway.getAddresses()[0].getPort();
    gatewayUrl = "http://localhost:" + gatewayPort + "/" + config.getGatewayPath();
    clusterUrl = gatewayUrl + "/test-topology";

    LOG.info( "Gateway port = " + gateway.getAddresses()[ 0 ].getPort() );

    params = new Properties();
    params.put( "LDAP_URL", "ldap://localhost:" + ldapPort );
    params.put( "WEBHDFS_URL", "http://localhost:" + mockWebHdfs.getPort() );
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testSimpleStaticHelloAppDeployUndeploy() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-static-hello-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";
    String serviceUrl = clusterUrl + "/static-hello-app-path/index.html";
    String body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/html" )
        .when().get( serviceUrl ).asString();
    assertThat( the(body), hasXPath( "/html/head/title/text()", equalTo("Static Hello Application") ) );

    serviceUrl = clusterUrl + "/static-hello-app-path/";
    body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/html" )
        .when().get( serviceUrl ).asString();
    assertThat( the(body), hasXPath( "/html/head/title/text()", equalTo("Static Hello Application") ) );

    serviceUrl = clusterUrl + "/static-hello-app-path";
    body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/html" )
        .when().get( serviceUrl ).asString();
    assertThat( the(body), hasXPath( "/html/head/title/text()", equalTo("Static Hello Application") ) );

    assertThat( "Failed to delete test topology file", FileUtils.deleteQuietly( topoFile ), is(true) );
    topos.reloadTopologies();

    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( serviceUrl );

    LOG_EXIT();
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testSimpleDynamicAppDeployUndeploy() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-dynamic-app-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";

    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
        .when().get( clusterUrl + "/dynamic-app-path" );

    assertThat( "Failed to delete test topology file", FileUtils.deleteQuietly( topoFile ), is(true) );
    topos.reloadTopologies();

    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when()
        .get( clusterUrl + "/dynamic-app-path" );

    LOG_EXIT();
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testNakedAppDeploy() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-naked-app-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    given()
        //.log().all()
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .body( is( gatewayUrl + "/test-topology/dynamic-app/?null" ) )
        .when().get( gatewayUrl + "/test-topology/dynamic-app" );

    LOG_EXIT();
  }

  @Test//( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testDefaultAppName() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-default-app-name-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";

    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .body( is( clusterUrl + "/dynamic-app/?null" ) )
        .when().get( clusterUrl + "/dynamic-app" );

    assertThat( "Failed to delete test topology file", FileUtils.deleteQuietly( topoFile ), is(true) );
    topos.reloadTopologies();

    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when()
        .get( clusterUrl + "/dynamic-app" );

    File deployDir = new File( config.getGatewayDeploymentDir() );
    assertThat( deployDir.listFiles(), is(arrayWithSize(0)) );

    LOG_EXIT();
  }

  @Test//( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testMultiApps() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-multi-apps-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";

    String body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/html" )
        .when().get( clusterUrl + "/static-hello-app-path/index.html" ).asString();
    assertThat( the(body), hasXPath( "/html/head/title/text()", equalTo("Static Hello Application") ) );

    body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        .contentType( "" )
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .when().get( clusterUrl + "/static-json-app/one.json" ).asString();
    assertThat( body, sameJSONAs( "{'test-name-one':'test-value-one'}" ) );

    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
        .when().get( clusterUrl + "/dynamic-app-path" );

    body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        .contentType( "application/xml" )
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .when().get( clusterUrl + "/test.xml" ).asString();
    assertThat( the(body), hasXPath( "/test" ) );

    assertThat( FileUtils.deleteQuietly( topoFile ), is(true) );
    topos.reloadTopologies();

    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/static-hello-app-path/index.html" );
    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/static-json-app/one.json" );
    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/dynamic-app-path" );
    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/test.xml" );

    LOG_EXIT();
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testServicesAndApplications() throws Exception {
    LOG_ENTER();

    String topoStr = TestUtils.merge( DAT, "test-svcs-and-apps-topology.xml", params );
    File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
    FileUtils.writeStringToFile( topoFile, topoStr );

    topos.reloadTopologies();

    String username = "guest";
    String password = "guest-password";

    mockWebHdfs.expect()
        .method( "GET" )
        .pathInfo( "/v1/" )
        .queryParam( "op", "GETHOMEDIRECTORY" )
        .queryParam( "user.name", "guest" )
        .respond()
        .status( HttpStatus.SC_OK )
        .content( "{\"path\":\"/users/guest\"}", Charset.forName("UTF-8") )
        .contentType( "application/json" );
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .queryParam( "op", "GETHOMEDIRECTORY" )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "application/json" )
        .body( "path", is( "/users/guest") )
        .when().get( clusterUrl + "/webhdfs/v1" );
    assertThat( mockWebHdfs.isEmpty(), is(true) );

    String body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "application/xml" )
        .when().get( clusterUrl + "/static-xml-app/test.xml" ).asString();
    assertThat( the(body), hasXPath( "test" ) );

    body = given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        .contentType( "" )
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .when().get( clusterUrl + "/app-two/one.json" ).asString();
    assertThat( body, sameJSONAs( "{'test-name-one':'test-value-one'}" ) );

    assertThat( FileUtils.deleteQuietly( topoFile ), is(true) );
    topos.reloadTopologies();

    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/app-one/index.html" );
    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/app-two/one.json" );
    given()
        .auth().preemptive().basic( username, password )
        .expect()
        .statusCode( HttpStatus.SC_NOT_FOUND )
        .when().get( clusterUrl + "/test.xml" );

    File deployDir = new File( config.getGatewayDeploymentDir() );
    assertThat( deployDir.listFiles(), is(arrayWithSize(0)) );

    LOG_EXIT();
  }

  @Test//( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testDeploymentCleanup() throws Exception {
    LOG_ENTER();

    String username = "guest";
    String password = "guest-password";

    int oldVersionLimit = config.getGatewayDeploymentsBackupVersionLimit();

    try {
      gateway.stop();
      config.setGatewayDeploymentsBackupVersionLimit( 1 );
      startGatewayServer();

      String topoStr = TestUtils.merge( DAT, "test-dynamic-app-topology.xml", params );
      File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
      FileUtils.writeStringToFile( topoFile, topoStr );
      topos.reloadTopologies();

      File deployDir = new File( config.getGatewayDeploymentDir() );
      String[] topoDirs1 = deployDir.list();
      assertThat( topoDirs1, is(arrayWithSize(1)) );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

      TestUtils.waitUntilNextSecond();
      FileUtils.touch( topoFile );

      topos.reloadTopologies();
      String[] topoDirs2 = deployDir.list();
      assertThat( topoDirs2, is(arrayWithSize(2)) );
      assertThat( topoDirs2, hasItemInArray(topoDirs1[0]) );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

      TestUtils.waitUntilNextSecond();
      FileUtils.touch( topoFile );
      topos.reloadTopologies();

      String[] topoDirs3 = deployDir.list();
      assertThat( topoDirs3, is(arrayWithSize(2)) );
      assertThat( topoDirs3, not(hasItemInArray(topoDirs1[0])) );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

    } finally {
      gateway.stop();
      config.setGatewayDeploymentsBackupAgeLimit( oldVersionLimit );
      startGatewayServer();
    }

    LOG_EXIT();
  }

  @Test( timeout = TestUtils.MEDIUM_TIMEOUT )
  public void testDefaultTopology() throws Exception {
    LOG_ENTER();

    try {
      gateway.stop();
      config.setGatewayDeploymentsBackupVersionLimit( 1 );
      startGatewayServer();

      String topoStr = TestUtils.merge( DAT, "test-dynamic-app-topology.xml", params );
      File topoFile = new File( config.getGatewayTopologyDir(), "test-topology.xml" );
      FileUtils.writeStringToFile( topoFile, topoStr );

      topos.reloadTopologies();

      File deployDir = new File( config.getGatewayDeploymentDir() );
      String[] topoDirs = deployDir.list();
      assertThat( topoDirs, is(arrayWithSize(1)) );

      String username = "guest";
      String password = "guest-password";

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

      topoStr = TestUtils.merge( DAT, "test-dynamic-app-topology.xml", params );
      topoFile = new File( config.getGatewayTopologyDir(), "test-topology-2.xml" );
      FileUtils.writeStringToFile( topoFile, topoStr );

      topos.reloadTopologies();

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( gatewayUrl + "/test-topology" + "/dynamic-app-path/?null" ) )
          .when().get( gatewayUrl + "/test-topology/dynamic-app-path" );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( gatewayUrl + "/test-topology-2" + "/dynamic-app-path/?null" ) )
          .when().get( gatewayUrl + "/test-topology-2/dynamic-app-path" );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_NOT_FOUND )
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) );

      gateway.stop();
      config.setDefaultTopologyName( "test-topology" );
      startGatewayServer();

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( gatewayUrl + "/test-topology" + "/dynamic-app-path/?null" ) )
          .when().get( gatewayUrl + "/test-topology/dynamic-app-path" );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .statusCode( HttpStatus.SC_OK )
          .body( is( gatewayUrl + "/test-topology-2" + "/dynamic-app-path/?null" ) )
          .when().get( gatewayUrl + "/test-topology-2/dynamic-app-path" );

      given()
          //.log().all()
          .auth().preemptive().basic( username, password )
          .expect()
          //.log().all()
          .body( is( clusterUrl + "/dynamic-app-path/?null" ) )
          .when().get( clusterUrl + "/dynamic-app-path" );

    } finally {
      gateway.stop();
      config.setDefaultTopologyName( null );
      startGatewayServer();
    }

    LOG_EXIT();
  }

  public static Collection<String> toNames( File[] files ) {
    List<String> names = new ArrayList<String>( files.length );
    for( File file : files ) {
      names.add( file.getAbsolutePath() );
    }
    return names;

  }

}
