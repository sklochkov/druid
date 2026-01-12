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

import com.google.common.collect.ImmutableMap;
import com.google.inject.Binder;
import com.google.inject.multibindings.Multibinder;
import org.eclipse.jetty.ee8.servlets.QoSFilter;
import org.eclipse.jetty.server.Handler;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.http.HttpServlet;
import java.util.EnumSet;
import java.util.Map;

public class JettyBindings
{
  private JettyBindings()
  {
    // No instantiation.
  }

  public static void addQosFilter(Binder binder, String path, int maxRequests)
  {
    addQosFilter(binder, new String[]{path}, maxRequests);
  }

  public static void addQosFilter(Binder binder, String[] paths, int maxRequests)
  {
    if (maxRequests <= 0) {
      return;
    }

    Multibinder.newSetBinder(binder, QosFilterHolder.class)
               .addBinding()
               .toInstance(new QosFilterHolder(paths, maxRequests));
  }

  public static void addHandler(Binder binder, Class<? extends Handler> handlerClass)
  {
    Multibinder.newSetBinder(binder, Handler.class)
               .addBinding()
               .to(handlerClass);
  }

  /**
   * Register a servlet to be added to the server's ServletContextHandler.
   * The servlet will be mapped to its specified path.
   */
  public static void addServletBinding(Binder binder, Class<? extends ServletBindingHolder> servletBindingClass)
  {
    Multibinder.newSetBinder(binder, ServletBindingHolder.class)
               .addBinding()
               .to(servletBindingClass);
  }

  /**
   * Holder for servlet bindings that specifies the servlet class and its path mapping.
   */
  public interface ServletBindingHolder
  {
    /**
     * The servlet class to instantiate via Guice.
     */
    Class<? extends HttpServlet> getServletClass();

    /**
     * The path spec for this servlet (e.g., "/druid/v2/sql/avatica/*").
     */
    String getPathSpec();
  }

  public static class QosFilterHolder implements ServletFilterHolder
  {
    private final String[] paths;
    private final int maxRequests;

    private final long timeoutMs;

    public QosFilterHolder(String[] paths, int maxRequests, long timeoutMs)
    {
      this.paths = paths;
      this.maxRequests = maxRequests;
      this.timeoutMs = timeoutMs;
    }

    public QosFilterHolder(String[] paths, int maxRequests)
    {
      this(paths, maxRequests, -1);
    }

    @Override
    public Filter getFilter()
    {
      return new QoSFilter();
    }

    @Override
    public Class<? extends Filter> getFilterClass()
    {
      return QoSFilter.class;
    }

    @Override
    public Map<String, String> getInitParameters()
    {
      if (timeoutMs < 0) {
        return ImmutableMap.of("maxRequests", String.valueOf(maxRequests));
      }
      if (timeoutMs > Integer.MAX_VALUE) {
        // QoSFilter tries to parse the suspendMs parameter as an int, so we can't set it to more than Integer
        // .MAX_VALUE.
        return ImmutableMap.of("maxRequests", String.valueOf(maxRequests), "suspendMs", String.valueOf(Integer.MAX_VALUE));
      }
      return ImmutableMap.of("maxRequests", String.valueOf(maxRequests), "suspendMs", String.valueOf(timeoutMs));
    }

    @Override
    public String getPath()
    {
      return null;
    }

    @Override
    public String[] getPaths()
    {
      return paths;
    }

    @Override
    public EnumSet<DispatcherType> getDispatcherType()
    {
      return null;
    }
  }
}
