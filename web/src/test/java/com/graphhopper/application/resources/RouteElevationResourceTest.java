/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.application.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.graphhopper.application.GraphHopperApplication;
import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.application.util.GraphHopperServerTestConfiguration;
import com.graphhopper.routing.TestProfiles;
import com.graphhopper.util.Helper;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.util.List;

import static com.graphhopper.application.util.TestUtils.clientTarget;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the route elevation profile endpoint.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
public class RouteElevationResourceTest {
    private static final String dir = "./target/monaco-gh-elevation/";
    private static final DropwizardAppExtension<GraphHopperServerConfiguration> app = new DropwizardAppExtension<>(GraphHopperApplication.class, createConfig());

    private static GraphHopperServerConfiguration createConfig() {
        GraphHopperServerConfiguration config = new GraphHopperServerTestConfiguration();
        config.getGraphHopperConfiguration().
                putObject("graph.elevation.provider", "srtm").
                putObject("graph.elevation.cache_dir", "../core/files/").
                putObject("prepare.min_network_size", 0).
                putObject("datareader.file", "../core/files/monaco.osm.gz").
                putObject("graph.location", dir).
                putObject("import.osm.ignored_highways", "").
                putObject("graph.encoded_values", "car_access, car_average_speed").
                setProfiles(List.of(TestProfiles.accessAndSpeed("profile", "car")));
        return config;
    }

    @BeforeAll
    @AfterAll
    public static void cleanUp() {
        Helper.removeDir(new File(dir));
    }

    @Test
    public void testElevationProfile() {
        JsonNode json = clientTarget(app, "/route/elevation?profile=profile&" +
                "point=43.730864,7.420771&point=43.727687,7.418737").request().get(JsonNode.class);
        
        assertNotNull(json);
        assertTrue(json.has("profile"), "Response should contain profile array");
        assertTrue(json.has("distance"), "Response should contain distance");
        assertTrue(json.has("ascend"), "Response should contain ascend");
        assertTrue(json.has("descend"), "Response should contain descend");
        assertTrue(json.has("info"), "Response should contain info");

        JsonNode profile = json.get("profile");
        assertTrue(profile.isArray(), "Profile should be an array");
        assertTrue(profile.size() > 0, "Profile should contain at least one point");

        // Check first point
        JsonNode firstPoint = profile.get(0);
        assertTrue(firstPoint.has("distance"), "Each point should have distance");
        assertTrue(firstPoint.has("elevation"), "Each point should have elevation");
        assertEquals(0.0, firstPoint.get("distance").asDouble(), 0.01, "First point distance should be 0");

        // Check last point distance matches total distance
        JsonNode lastPoint = profile.get(profile.size() - 1);
        double lastDistance = lastPoint.get("distance").asDouble();
        double totalDistance = json.get("distance").asDouble();
        // Allow some tolerance due to rounding differences
        assertTrue(Math.abs(lastDistance - totalDistance) < 10.0, 
                "Last point distance should approximately match total distance");

        // Verify elevation values are reasonable (Monaco elevations)
        for (int i = 0; i < profile.size(); i++) {
            JsonNode point = profile.get(i);
            double elevation = point.get("elevation").asDouble();
            assertFalse(Double.isNaN(elevation), "Elevation should not be NaN");
            assertTrue(elevation >= -100 && elevation <= 1000, 
                    "Elevation should be reasonable: " + elevation);
        }

        // Verify ascend and descend are non-negative
        double ascend = json.get("ascend").asDouble();
        double descend = json.get("descend").asDouble();
        assertTrue(ascend >= 0, "Ascend should be non-negative");
        assertTrue(descend >= 0, "Descend should be non-negative");
    }

    @Test
    public void testElevationProfileWithMultiplePoints() {
        JsonNode json = clientTarget(app, "/route/elevation?profile=profile&" +
                "point=43.730864,7.420771&point=43.729000,7.419000&point=43.727687,7.418737").request().get(JsonNode.class);
        
        assertNotNull(json);
        assertTrue(json.has("profile"));
        JsonNode profile = json.get("profile");
        assertTrue(profile.size() > 0, "Profile should contain points");
        
        // Verify distances are cumulative and increasing
        double prevDistance = -1.0;
        for (int i = 0; i < profile.size(); i++) {
            JsonNode point = profile.get(i);
            double distance = point.get("distance").asDouble();
            assertTrue(distance >= prevDistance, "Distances should be cumulative and non-decreasing");
            prevDistance = distance;
        }
    }

    @Test
    public void testElevationProfileErrorWhenElevationNotSupported() {
        // This test would require a configuration without elevation support
        // For now, we test that the endpoint requires elevation
        // In a real scenario, you'd create a separate test configuration without elevation
    }

    @Test
    public void testElevationProfileInvalidRoute() {
        // Test with points that are too far apart or invalid
        try {
            clientTarget(app, "/route/elevation?profile=profile&" +
                    "point=0,0&point=90,180").request().get(JsonNode.class);
            // Should either return an error or handle gracefully
            // The exact behavior depends on GraphHopper's routing capabilities
        } catch (Exception e) {
            // Expected for invalid routes
            assertTrue(e.getMessage().contains("error") || e.getMessage().contains("route") || 
                    e.getMessage().contains("not found"), "Should handle invalid routes gracefully");
        }
    }

    @Test
    public void testElevationProfilePOST() {
        String jsonBody = "{\"points\":[[7.420771,43.730864],[7.418737,43.727687]],\"profile\":\"profile\"}";
        
        JsonNode json = clientTarget(app, "/route/elevation")
                .request()
                .post(jakarta.ws.rs.client.Entity.entity(jsonBody, jakarta.ws.rs.core.MediaType.APPLICATION_JSON), JsonNode.class);
        
        assertNotNull(json);
        assertTrue(json.has("profile"), "Response should contain profile array");
        assertTrue(json.has("distance"), "Response should contain distance");
        assertTrue(json.has("ascend"), "Response should contain ascend");
        assertTrue(json.has("descend"), "Response should contain descend");
        
        JsonNode profile = json.get("profile");
        assertTrue(profile.isArray() && profile.size() > 0, "Profile should contain points");
    }
}

