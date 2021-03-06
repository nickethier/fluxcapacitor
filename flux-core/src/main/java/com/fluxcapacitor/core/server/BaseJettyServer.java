/*
 * 	Copyright 2012 Chris Fregly
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.fluxcapacitor.core.server;

import java.io.Closeable;

import org.apache.jasper.servlet.JspServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fluxcapacitor.core.config.AppConfiguration;
import com.fluxcapacitor.core.metrics.AppMetrics;
import com.fluxcapacitor.core.util.InetAddressUtils;
import com.google.common.io.Closeables;
import com.google.inject.Injector;
import com.netflix.blitz4j.LoggingConfiguration;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.hystrix.contrib.metrics.eventstream.HystrixMetricsStreamServlet;
import com.netflix.karyon.server.KaryonServer;
import com.sun.jersey.api.core.PackagesResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;


/**
 * @author Chris Fregly (chris@fregly.com)
 */
public class BaseJettyServer implements Closeable {
	private static final Logger logger = LoggerFactory
			.getLogger(BaseJettyServer.class);

	public final Server jettyServer;

	public final KaryonServer karyonServer;

	public String host;
	public int port;

	protected final Injector injector;

	protected AppConfiguration config;
	protected AppMetrics metrics;

	public BaseJettyServer() {
		// This must be set before karyonServer.initialize() otherwise the
		// archaius properties will not be available in JMX/jconsole
		System.setProperty(DynamicPropertyFactory.ENABLE_JMX, "true");

		this.karyonServer = new KaryonServer();
		this.injector = karyonServer.initialize();
		
		this.jettyServer = new Server();
	}

	public void start() {
		LoggingConfiguration.getInstance().configure();

		try {
			karyonServer.start();
		} catch (Exception exc) {
			throw new RuntimeException("Cannot start karyon server.", exc);
		}

		// Note:  after karyonServer.start(), the service will be marked as UP in eureka discovery.
		//		  this is not ideal, but we need to call karyonServer.start() in order to start the Guice LifecyleManager 
		//			to ultimately get the FluxConfiguration in the next step...

		config = injector.getInstance(AppConfiguration.class);
		metrics = injector.getInstance(AppMetrics.class);
		
		port = config.getInt("jetty.http.port", Integer.MIN_VALUE);
		host = InetAddressUtils.getBestReachableIp();

		// NOTE: make sure any changes made here are reflected in web.xml -->

		// Thanks to Aaron Wirtz for this snippet:
		//
		final Context context = new Context(jettyServer, "/", Context.SESSIONS);
		context.setResourceBase("webapp");
		context.setClassLoader(Thread.currentThread().getContextClassLoader());
		
		// enable jsp's
		context.addServlet(JspServlet.class, "/jsp/*.jsp");
		
		// enable Jersey REST endpoints
		final PackagesResourceConfig rcf = new PackagesResourceConfig(config.getString("jersey.resources.package", "not-found-in-configuration"));
		final ServletContainer container = new ServletContainer(rcf);
		context.addServlet(new ServletHolder(container), "/service/*");

		// enable hystrix.stream
		context.addServlet(HystrixMetricsStreamServlet.class, "/hystrix.stream");

		try {
			metrics.start();
		} catch (Exception exc) {
			logger.error("Error starting metrics publisher.", exc);
		}

		final Server server = new Server(port);
		server.setHandler(context);

		try {
			server.start();
		} catch (Exception exc) {
			logger.error("Error starting jetty.", exc);
			throw new RuntimeException("Error starting jetty.", exc);
		}			
	}

	@Override
	public void close() {
		try {
			jettyServer.stop();
			Closeables.closeQuietly(karyonServer);
			Closeables.closeQuietly(metrics);
		} catch (Exception exc) {
			logger.error("Error shutting down jetty.", exc);
		}
		LoggingConfiguration.getInstance().stop();
	}
}
