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

package org.apache.druid.sql.avatica;

import com.google.inject.Inject;
import org.apache.druid.guice.annotations.Self;
import org.apache.druid.server.DruidNode;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet wrapper for DruidAvaticaJsonHandler for Jetty 12 compatibility.
 * The Avatica library's AvaticaJsonHandler extends Jetty 9's AbstractHandler,
 * which is incompatible with Jetty 12's Handler interface. This servlet delegates
 * to the underlying handler which still uses javax.servlet APIs.
 */
public class DruidAvaticaJsonServlet extends HttpServlet
{
  public static final String AVATICA_PATH = DruidAvaticaJsonHandler.AVATICA_PATH;
  public static final String AVATICA_PATH_NO_TRAILING_SLASH = DruidAvaticaJsonHandler.AVATICA_PATH_NO_TRAILING_SLASH;

  private final DruidAvaticaJsonHandler handler;

  @Inject
  public DruidAvaticaJsonServlet(
      final DruidMeta druidMeta,
      @Self final DruidNode druidNode,
      final AvaticaMonitor avaticaMonitor
  )
  {
    this.handler = new DruidAvaticaJsonHandler(druidMeta, druidNode, avaticaMonitor);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException
  {
    // The underlying handler uses the old Jetty 9 API, but it only needs the servlet request/response
    // objects which are compatible with javax.servlet. We pass null for the baseRequest since
    // the handler's logic only needs the servlet request/response.
    handler.handle(request.getRequestURI(), null, request, response);
  }
}
