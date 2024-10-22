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

package org.apache.druid.iceberg.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.iceberg.catalog.IcebergCatalog;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class HiveIcebergCatalogTest
{
  private final ObjectMapper mapper = new DefaultObjectMapper();

  @Test
  public void testCatalogCreate()
  {
    Map<String, String> options = new HashMap<>();
    options.put(CatalogUtil.ICEBERG_CATALOG_TYPE, CatalogUtil.ICEBERG_CATALOG_TYPE_HIVE);
    options.put(CatalogProperties.WAREHOUSE_LOCATION, "hdfs://testuri");
    IcebergCatalog hiveCatalog = new IcebergCatalog(
        "hive",
        options,
        new Configuration(),
        false
    );
      IcebergCatalog hiveCatalogNullProps = new IcebergCatalog(
              "hive",
              options,
              new Configuration(),
              null
      );
    Assert.assertEquals("hive", hiveCatalog.retrieveCatalog().name());
    Assert.assertEquals(2, hiveCatalogNullProps.getCatalogProperties().size());
  }

}
