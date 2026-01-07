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

package org.apache.druid.discovery;

import com.google.common.collect.Lists;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.server.DruidNode;

import javax.annotation.Nullable;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utils class for shared client methods
 */
public class ClientUtils
{
  private static final Logger LOG = new Logger(ClientUtils.class);

  @Nullable
  public static String pickOneHost(DruidNodeDiscovery druidNodeDiscovery)
  {
    Iterator<DiscoveryDruidNode> iter = druidNodeDiscovery.getAllNodes().iterator();
    List<DiscoveryDruidNode> discoveryDruidNodeList = Lists.newArrayList(iter);
    if (!discoveryDruidNodeList.isEmpty()) {
      DiscoveryDruidNode node = discoveryDruidNodeList.get(ThreadLocalRandom.current().nextInt(discoveryDruidNodeList.size()));
      DruidNode druidNode = node.getDruidNode();
      
      String scheme = druidNode.getServiceScheme();
      String hostAndPort = druidNode.getHostAndPortToUse();
      
      // Check for control characters in the discovered host URL components
      // This helps diagnose Netty 4.1.129+ URI validation failures
      if (containsControlCharacters(scheme) || containsControlCharacters(hostAndPort)) {
        LOG.warn(
            "Discovered node contains control characters! scheme=[%s] (hex: %s), hostAndPort=[%s] (hex: %s), "
            + "serviceName=[%s]. This will cause Netty 4.1.129+ URI validation to fail.",
            escapeControlChars(scheme),
            toHexString(scheme),
            escapeControlChars(hostAndPort),
            toHexString(hostAndPort),
            druidNode.getServiceName()
        );
      }
      
      return StringUtils.format(
          "%s://%s",
          scheme,
          hostAndPort
      );
    }
    return null;
  }

  /**
   * Checks if a string contains control characters (0x00-0x1F or 0x7F).
   */
  private static boolean containsControlCharacters(@Nullable String s)
  {
    if (s == null) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return true;
      }
    }
    return false;
  }

  /**
   * Escapes control characters for logging visibility.
   */
  private static String escapeControlChars(@Nullable String s)
  {
    if (s == null) {
      return "null";
    }
    return s.replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
  }

  /**
   * Converts string to hex representation for debugging.
   */
  private static String toHexString(@Nullable String s)
  {
    if (s == null) {
      return "null";
    }
    StringBuilder hex = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (i > 0) {
        hex.append(" ");
      }
      hex.append(String.format("%02X", (int) s.charAt(i)));
    }
    return hex.toString();
  }

  public static Request withUrl(Request old, URL url)
  {
    Request req = new Request(old.getMethod(), url);
    req.addHeaderValues(old.getHeaders());
    if (old.hasContent()) {
      req.setContent(old.getContent().copy());
    }
    return req;
  }
}
