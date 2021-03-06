package com.google.api.server.spi.testing;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;

/**
 * Testing for API methods that have absolute paths.
 */
@Api(name = "absolutepath", version = "v1")
public class AbsoluteCommonPathEndpoint {
  @ApiMethod(name = "create", path = "create")
  public Foo createFoo() {
    return null;
  }

  @ApiMethod(name = "absolutepath", path = "/absolutepath/v1/absolutepathmethod")
  public void absolutePath() { }
}
