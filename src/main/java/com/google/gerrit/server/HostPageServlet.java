// Copyright (C) 2008 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server;

import com.google.gerrit.client.rpc.Common;
import com.google.gwt.user.server.rpc.RPCServletUtils;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.gwtorm.client.OrmException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** Sends the Gerrit host page to clients. */
public class HostPageServlet extends HttpServlet {
  static final long MAX_AGE = 5 * 60 * 1000L/* milliseconds */;
  static final String CACHE_CTRL = "public, max-age=" + (MAX_AGE / 1000L);

  private String canonicalUrl;
  private byte[] hostPageRaw;
  private byte[] hostPageCompressed;
  private long lastModified;

  @Override
  public void init(final ServletConfig config) throws ServletException {
    super.init(config);

    final GerritServer srv;
    try {
      srv = GerritServer.getInstance();
    } catch (OrmException e) {
      throw new ServletException("Cannot load GerritServer", e);
    } catch (XsrfException e) {
      throw new ServletException("Cannot load GerritServer", e);
    }

    final File sitePath = srv.getSitePath();
    canonicalUrl = srv.getCanonicalURL();

    final String hostPageName = "com/google/gerrit/public/Gerrit.html";
    final Document hostDoc = HtmlDomUtil.parseFile(hostPageName);
    if (hostDoc == null) {
      throw new ServletException("No " + hostPageName + " in CLASSPATH");
    }
    injectJson(hostDoc, "gerrit_gerritconfig", Common.getGerritConfig());
    injectCssFile(hostDoc, "gerrit_sitecss", sitePath, "GerritSite.css");
    injectXmlFile(hostDoc, "gerrit_header", sitePath, "GerritSiteHeader.html");
    injectXmlFile(hostDoc, "gerrit_footer", sitePath, "GerritSiteFooter.html");
    try {
      hostPageRaw = HtmlDomUtil.toUTF8(hostDoc);
      hostPageCompressed = HtmlDomUtil.compress(hostPageRaw);
    } catch (IOException e) {
      throw new ServletException(e.getMessage(), e);
    }
    lastModified = System.currentTimeMillis();
  }

  private void injectXmlFile(final Document hostDoc, final String id,
      final File sitePath, final String fileName) throws ServletException {
    final Element banner = HtmlDomUtil.find(hostDoc, id);
    if (banner == null) {
      return;
    }

    while (banner.getFirstChild() != null) {
      banner.removeChild(banner.getFirstChild());
    }

    final Document html = HtmlDomUtil.parseFile(sitePath, fileName);
    if (html == null) {
      banner.getParentNode().removeChild(banner);
      return;
    }

    final Element content = html.getDocumentElement();
    banner.appendChild(hostDoc.importNode(content, true));
  }

  private void injectCssFile(final Document hostDoc, final String id,
      final File sitePath, final String fileName) throws ServletException {
    final Element banner = HtmlDomUtil.find(hostDoc, id);
    if (banner == null) {
      return;
    }

    while (banner.getFirstChild() != null) {
      banner.removeChild(banner.getFirstChild());
    }

    final String css = HtmlDomUtil.readFile(sitePath, fileName);
    if (css == null) {
      banner.getParentNode().removeChild(banner);
      return;
    }

    banner.removeAttribute("id");
    banner.appendChild(hostDoc.createCDATASection("\n" + css + "\n"));
  }

  private void injectJson(final Document hostDoc, final String id,
      final Object obj) {
    final Element scriptNode = HtmlDomUtil.find(hostDoc, id);
    if (scriptNode == null) {
      return;
    }

    while (scriptNode.getFirstChild() != null) {
      scriptNode.removeChild(scriptNode.getFirstChild());
    }

    if (obj == null) {
      scriptNode.getParentNode().removeChild(scriptNode);
      return;
    }

    final StringWriter w = new StringWriter();
    w.write("<!--\n");
    w.write("var ");
    w.write(id);
    w.write("_obj=");
    GerritJsonServlet.defaultGsonBuilder().create().toJson(obj, w);
    w.write(";\n// -->\n");
    scriptNode.removeAttribute("id");
    scriptNode.setAttribute("type", "text/javascript");
    scriptNode.setAttribute("language", "javascript");
    scriptNode.appendChild(hostDoc.createCDATASection(w.toString()));
  }

  @Override
  protected long getLastModified(final HttpServletRequest req) {
    return lastModified;
  }

  @Override
  protected void doGet(final HttpServletRequest req,
      final HttpServletResponse rsp) throws IOException {

    // If we get a request for "/Gerrit/change,1" rewrite it the way
    // it should have been, as "/Gerrit#change,1". This may happen
    // coming out of Google Analytics, where its common to replace
    // the anchor mark ('#') with '/' so it logs independent pages.
    //
    final String screen = req.getPathInfo();
    if (screen != null && screen.length() > 1 && screen.startsWith("/")) {
      final StringBuilder r = new StringBuilder();
      if (canonicalUrl != null) {
        r.append(canonicalUrl);
      } else {
        r.append(GerritServer.serverUrl(req));
      }
      r.append("Gerrit#");
      r.append(screen.substring(1));
      rsp.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      rsp.setHeader("Location", r.toString());
      return;
    }

    if (canonicalUrl != null
        && !canonicalUrl.equals(GerritServer.serverUrl(req))) {
      rsp.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      rsp.setHeader("Location", canonicalUrl + "Gerrit");
      return;
    }

    final byte[] tosend;
    if (RPCServletUtils.acceptsGzipEncoding(req)) {
      rsp.setHeader("Content-Encoding", "gzip");
      tosend = hostPageCompressed;
    } else {
      tosend = hostPageRaw;
    }

    rsp.setHeader("Cache-Control", CACHE_CTRL);
    rsp.setDateHeader("Expires", System.currentTimeMillis() + MAX_AGE);
    rsp.setDateHeader("Last-Modified", lastModified);
    rsp.setContentType("text/html");
    rsp.setCharacterEncoding(HtmlDomUtil.ENC);
    rsp.setContentLength(tosend.length);
    final OutputStream out = rsp.getOutputStream();
    try {
      out.write(tosend);
    } finally {
      out.close();
    }
  }
}
