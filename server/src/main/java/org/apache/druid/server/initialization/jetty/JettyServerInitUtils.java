/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.initialization.jetty;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.security.AllowHttpMethodsResourceFilter;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.FilterMapping;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.rewrite.handler.HeaderPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;

import javax.ws.rs.HttpMethod;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class JettyServerInitUtils
{
  private static final String[] GZIP_METHODS = new String[]{HttpMethod.GET, HttpMethod.POST};

  public static GzipHandler wrapWithDefaultGzipHandler(final Handler handler, int inflateBufferSize, int compressionLevel)
  {
    GzipHandler gzipHandler = new GzipHandler();
    gzipHandler.setMinGzipSize(0);
    gzipHandler.setIncludedMethods(GZIP_METHODS);
    gzipHandler.setInflateBufferSize(inflateBufferSize);
    // Note: In Jetty 12, compression level is set via DeflaterPool on the Server.
    // The compressionLevel parameter is no longer directly configurable on GzipHandler.
    // setCheckGzExists was also removed in Jetty 12.
    gzipHandler.setHandler(handler);
    return gzipHandler;
  }

  /**
   * Configure gzip compression for an EE8 ServletContextHandler.
   * In Jetty 12 EE8, we use insertHandler to insert the GzipHandler into the handler chain
   * of the core context, which properly integrates with the servlet layer.
   * 
   * @param handler the EE8 ServletContextHandler to configure
   * @param inflateBufferSize size of the inflate buffer
   * @param compressionLevel compression level (note: not used in Jetty 12, kept for API compatibility)
   */
  public static void configureGzipHandler(final ServletContextHandler handler, int inflateBufferSize, int compressionLevel)
  {
    GzipHandler gzipHandler = new GzipHandler();
    gzipHandler.setMinGzipSize(0);
    gzipHandler.setIncludedMethods(GZIP_METHODS);
    gzipHandler.setInflateBufferSize(inflateBufferSize);
    // Insert the GzipHandler into the core context's handler chain
    // This properly integrates gzip with the EE8 servlet layer
    handler.getCoreContextHandler().insertHandler(gzipHandler);
  }

  /**
   * Overload for EE8 ServletContextHandler - gets the core context handler from the EE8 handler.
   * @deprecated Use {@link #configureGzipHandler(ServletContextHandler, int, int)} for proper EE8 integration
   */
  @Deprecated
  public static GzipHandler wrapWithDefaultGzipHandler(final ServletContextHandler handler, int inflateBufferSize, int compressionLevel)
  {
    return wrapWithDefaultGzipHandler(handler.getCoreContextHandler(), inflateBufferSize, compressionLevel);
  }

  /**
   * Add any filters that were registered with {@link JettyBindings#addQosFilter}. These must be added first in
   * the filter chain, because when a request is suspended and later resumed due to QoS constraints, its filter
   * chain is restarted. Placing QoSFilters first in the chain avoids double-execution of other filters.
   */
  public static void addQosFilters(ServletContextHandler handler, Injector injector)
  {
    final Set<JettyBindings.QosFilterHolder> filters =
        injector.getInstance(Key.get(new TypeLiteral<Set<JettyBindings.QosFilterHolder>>() {}));
    addFilters(handler, filters);
  }

  public static void addExtensionFilters(ServletContextHandler handler, Injector injector)
  {
    final Set<ServletFilterHolder> filters =
        injector.getInstance(Key.get(new TypeLiteral<Set<ServletFilterHolder>>() {}));
    addFilters(handler, filters);
  }

  /**
   * Add any servlets that were registered with {@link JettyBindings#addServletBinding}.
   */
  public static void addServletBindings(ServletContextHandler handler, Injector injector)
  {
    final Set<JettyBindings.ServletBindingHolder> servletBindings =
        injector.getInstance(Key.get(new TypeLiteral<Set<JettyBindings.ServletBindingHolder>>() {}));
    for (JettyBindings.ServletBindingHolder binding : servletBindings) {
      handler.addServlet(
          new org.eclipse.jetty.ee8.servlet.ServletHolder(injector.getInstance(binding.getServletClass())),
          binding.getPathSpec()
      );
    }
  }

  public static void addFilters(ServletContextHandler handler, Set<? extends ServletFilterHolder> filterHolders)
  {
    for (ServletFilterHolder servletFilterHolder : filterHolders) {
      // Check the Filter first to guard against people who don't read the docs and return the Class even
      // when they have an instance.
      FilterHolder holder;
      if (servletFilterHolder.getFilter() != null) {
        holder = new FilterHolder(servletFilterHolder.getFilter());
      } else if (servletFilterHolder.getFilterClass() != null) {
        holder = new FilterHolder(servletFilterHolder.getFilterClass());
      } else {
        throw new ISE(
            "Filter[%s] for paths[%s] didn't have a Filter!?",
            servletFilterHolder,
            Arrays.toString(servletFilterHolder.getPaths())
        );
      }

      if (servletFilterHolder.getInitParameters() != null) {
        holder.setInitParameters(servletFilterHolder.getInitParameters());
      }

      FilterMapping filterMapping = new FilterMapping();
      filterMapping.setFilterName(holder.getName());
      filterMapping.setPathSpecs(servletFilterHolder.getPaths());
      filterMapping.setDispatcherTypes(servletFilterHolder.getDispatcherType());

      handler.getServletHandler().addFilter(holder, filterMapping);
    }
  }

  public static void addAllowHttpMethodsFilter(ServletContextHandler root, List<String> allowedHttpMethods)
  {
    FilterHolder holder = new FilterHolder(new AllowHttpMethodsResourceFilter(allowedHttpMethods));
    root.addFilter(
        holder,
        "/*",
        null
    );
  }
  
  public static void maybeAddHSTSPatternRule(ServerConfig serverConfig, RewriteHandler rewriteHandler)
  {
    if (serverConfig.isEnableHSTS()) {
      rewriteHandler.addRule(getHSTSHeaderPattern());
    }
  }

  /**
   * Creates an HSTS rewrite handler if HSTS is enabled.
   * @return RewriteHandler with HSTS rule, or null if HSTS is not enabled
   */
  public static RewriteHandler createHSTSRewriteHandler(ServerConfig serverConfig)
  {
    if (serverConfig.isEnableHSTS()) {
      RewriteHandler rewriteHandler = new RewriteHandler();
      rewriteHandler.addRule(getHSTSHeaderPattern());
      return rewriteHandler;
    }
    return null;
  }

  public static HeaderPatternRule getHSTSHeaderPattern()
  {
    return new HeaderPatternRule("*", "Strict-Transport-Security", "max-age=63072000; includeSubDomains");
  }
}
