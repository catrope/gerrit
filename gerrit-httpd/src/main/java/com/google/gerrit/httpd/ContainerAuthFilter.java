// Copyright (C) 2011 The Android Open Source Project
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

package com.google.gerrit.httpd;

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gwtjsonrpc.server.XsrfException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

/**
 * Trust the authentication which is done by the container.
 * <p>
 * Check whether the container has already authenticated the user. If yes, then
 * lookup the account and set the account ID in our current session.
 * <p>
 * This filter should only be configured to run, when authentication is
 * configured to trust container authentication. This filter is intended only to
 * protect the {@link ProjectServlet} and its handled URLs, which provide remote
 * repository access over HTTP.
 */
@Singleton
class ContainerAuthFilter implements Filter {
  public static final String REALM_NAME = "Gerrit Code Review";

  private final Provider<WebSession> session;
  private final AccountCache accountCache;

  @Inject
  ContainerAuthFilter(Provider<WebSession> session, AccountCache accountCache)
      throws XsrfException {
    this.session = session;
    this.accountCache = accountCache;
  }

  @Override
  public void init(FilterConfig config) {
  }

  @Override
  public void destroy() {
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response,
      FilterChain chain) throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponseWrapper rsp =
        new HttpServletResponseWrapper((HttpServletResponse) response);

    if (verify(req, rsp)) {
      chain.doFilter(req, response);
    }
  }

  private boolean verify(HttpServletRequest req, HttpServletResponseWrapper rsp)
      throws IOException {
    final String username = req.getRemoteUser();
    final AccountState who =
        (username == null) ? null : accountCache.getByUsername(username);
    if (who == null || !who.getAccount().isActive()) {
      rsp.sendError(SC_UNAUTHORIZED);
      return false;
    }
    session.get().setUserAccountId(who.getAccount().getId());
    return true;
  }
}
