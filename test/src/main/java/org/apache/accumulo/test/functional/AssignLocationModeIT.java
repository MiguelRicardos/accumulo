/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.functional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.TabletLocationState;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.accumulo.server.manager.state.MetaDataTableScanner;
import org.apache.accumulo.test.util.Wait;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;

public class AssignLocationModeIT extends ConfigurableMacBase {

  @SuppressWarnings("deprecation")
  private static final Property DEPRECATED_TSERV_LAST_LOCATION_MODE_PROPERTY =
      Property.TSERV_LAST_LOCATION_MODE;

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(2);
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration fsConf) {
    cfg.setProperty(DEPRECATED_TSERV_LAST_LOCATION_MODE_PROPERTY, "assignment");
  }

  @Test
  public void test() throws Exception {
    try (AccumuloClient c =
        getCluster().createAccumuloClient("root", new PasswordToken(ROOT_PASSWORD))) {
      String tableName = super.getUniqueNames(1)[0];
      c.tableOperations().create(tableName);
      String tableId = c.tableOperations().tableIdMap().get(tableName);
      // wait for the table to be online
      Wait.waitFor(() -> getTabletLocationState(c, tableId) != null, SECONDS.toMillis(60), 250);

      TabletLocationState newTablet = getTabletLocationState(c, tableId);
      // this would be null if the mode was not "assign"
      assertEquals(newTablet.getCurrentServer(), newTablet.getLastServer());
      assertNull(newTablet.future);

      // put something in it
      try (BatchWriter bw = c.createBatchWriter(tableName)) {
        Mutation m = new Mutation("a");
        m.put("b", "c", "d");
        bw.addMutation(m);
      }
      // assert that the default mode is "assign"
      assertEquals("assignment", c.instanceOperations().getSystemConfiguration()
          .get(DEPRECATED_TSERV_LAST_LOCATION_MODE_PROPERTY.getKey()));

      // last location should not be set yet
      TabletLocationState unflushed = getTabletLocationState(c, tableId);
      assertEquals(newTablet.current, unflushed.current);
      assertEquals(newTablet.getCurrentServer(), unflushed.getLastServer());
      assertNull(newTablet.future);

      // take the tablet offline
      c.tableOperations().offline(tableName, true);
      TabletLocationState offline = getTabletLocationState(c, tableId);
      assertNull(offline.future);
      assertNull(offline.current);
      assertEquals(newTablet.getCurrentServer(), offline.getLastServer());

      // put it back online, should have the same last location
      c.tableOperations().online(tableName, true);
      TabletLocationState online = getTabletLocationState(c, tableId);
      assertNull(online.future);
      assertNotNull(online.current);
      assertEquals(newTablet.last, online.last);
    }
  }

  private TabletLocationState getTabletLocationState(AccumuloClient c, String tableId) {
    try (MetaDataTableScanner s = new MetaDataTableScanner((ClientContext) c,
        new Range(TabletsSection.encodeRow(TableId.of(tableId), null)), MetadataTable.NAME)) {
      return s.next();
    }
  }
}
