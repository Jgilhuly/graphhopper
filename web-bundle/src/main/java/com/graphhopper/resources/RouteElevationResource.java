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
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.ResponsePath;
import com.graphhopper.http.GHPointParam;
import com.graphhopper.http.GHRequestTransformer;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.GHPoint;
import io.dropwizard.jersey.params.AbstractParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.Arrays;
import java.util.List;

import static com.graphhopper.util.Parameters.Details.PATH_DETAILS;
import static com.graphhopper.util.Parameters.Routing.*;
import static java.util.stream.Collectors.toList;

/**
 * Resource for retrieving elevation profile data along a calculated route.
 * This endpoint enables applications to display elevation profiles and calculate
 * total elevation gain/loss for cycling and hiking routes.
 *
 * @author GraphHopper
 */
@Path("route/elevation")
public class RouteElevationResource {

    private static final Logger logger = LoggerFactory.getLogger(RouteElevationResource.class);

    private final GraphHopperConfig config;
    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;
    private final GHRequestTransformer ghRequestTransformer;
    private final Boolean hasElevation;
    private final String osmDate;
    private final List<String> snapPreventionsDefault;
    private final DistanceCalc distanceCalc = DistanceCalcEarth.DIST_EARTH;

    @Inject
    public RouteElevationResource(GraphHopperConfig config, GraphHopper graphHopper, ProfileResolver profileResolver, GHRequestTransformer ghRequestTransformer, @Named("hasElevation") Boolean hasElevation) {
        this.config = config;
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.ghRequestTransformer = ghRequestTransformer;
        this.hasElevation = hasElevation;
        this.osmDate = graphHopper.getProperties().getAll().get("datareader.data.date");
        this.snapPreventionsDefault = Arrays.stream(config.getString("routing.snap_preventions_default", "")
                .split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response doGet(
            @Context HttpServletRequest httpReq,
            @Context UriInfo uriInfo,
            @QueryParam(WAY_POINT_MAX_DISTANCE) @DefaultValue("0.5") double minPathPrecision,
            @QueryParam(ELEVATION_WAY_POINT_MAX_DISTANCE) Double minPathElevationPrecision,
            @QueryParam("point") @NotNull List<GHPointParam> pointParams,
            @QueryParam("profile") String profileName,
            @QueryParam(ALGORITHM) @DefaultValue("") String algoStr,
            @QueryParam("locale") @DefaultValue("en") String localeStr,
            @QueryParam(POINT_HINT) List<String> pointHints,
            @QueryParam(CURBSIDE) List<String> curbsides,
            @QueryParam(SNAP_PREVENTION) List<String> snapPreventions,
            @QueryParam(PATH_DETAILS) List<String> pathDetails,
            @QueryParam("heading") @NotNull List<Double> headings) {
        
        if (!hasElevation) {
            throw new IllegalArgumentException("Elevation not supported! Please enable elevation in the GraphHopper configuration.");
        }

        StopWatch sw = new StopWatch().start();
        List<GHPoint> points = pointParams.stream().map(AbstractParam::get).collect(toList());

        GHRequest request = new GHRequest();
        RouteResource.initHints(request.getHints(), uriInfo.getQueryParameters());

        if (minPathElevationPrecision != null)
            request.getHints().putObject(ELEVATION_WAY_POINT_MAX_DISTANCE, minPathElevationPrecision);

        request.setPoints(points).
                setProfile(profileName).
                setAlgorithm(algoStr).
                setLocale(localeStr).
                setHeadings(headings).
                setPointHints(pointHints).
                setCurbsides(curbsides).
                setPathDetails(pathDetails).
                getHints().
                putObject(CALC_POINTS, true).
                putObject(INSTRUCTIONS, false).
                putObject("elevation", true).
                putObject(WAY_POINT_MAX_DISTANCE, minPathPrecision);

        if (uriInfo.getQueryParameters().containsKey(SNAP_PREVENTION)) {
            if (snapPreventions.size() == 1 && snapPreventions.contains(""))
                request.setSnapPreventions(List.of());
            else
                request.setSnapPreventions(snapPreventions);
        } else {
            request.setSnapPreventions(snapPreventionsDefault);
        }

        request = ghRequestTransformer.transformRequest(request);

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", profileName);
        profileResolverHints.putObject("has_curbsides", !curbsides.isEmpty());
        profileName = profileResolver.resolveProfile(profileResolverHints);
        RouteResource.removeLegacyParameters(request.getHints());
        request.setProfile(profileName);

        GHResponse ghResponse = graphHopper.route(request);

        double took = sw.stop().getMillisDouble();
        String logStr = (httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent")) + " " + points + ", took: " + String.format("%.1f", took) + "ms, algo: " + algoStr + ", profile: " + profileName;

        if (ghResponse.hasErrors()) {
            logger.info(logStr + " " + ghResponse);
            return Response.status(Response.Status.BAD_REQUEST).
                    entity(new MultiException(ghResponse.getErrors())).
                    type(MediaType.APPLICATION_JSON).
                    build();
        }

        ResponsePath bestPath = ghResponse.getBest();
        if (bestPath == null || bestPath.getPoints().isEmpty()) {
            logger.warn(logStr + " - No route found");
            return Response.status(Response.Status.NOT_FOUND).
                    entity("No route found").
                    type(MediaType.APPLICATION_JSON).
                    build();
        }

        logger.info(logStr + ", distance: " + bestPath.getDistance() + ", points: " + bestPath.getPoints().size());

        ObjectNode response = buildElevationProfileResponse(bestPath, took);
        return Response.ok(response).
                header("X-GH-Took", "" + Math.round(took)).
                type(MediaType.APPLICATION_JSON).
                build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response doPost(@NotNull GHRequest request, @Context HttpServletRequest httpReq) {
        if (!hasElevation) {
            throw new IllegalArgumentException("Elevation not supported! Please enable elevation in the GraphHopper configuration.");
        }

        if (!request.hasSnapPreventions())
            request.setSnapPreventions(snapPreventionsDefault);

        StopWatch sw = new StopWatch().start();
        request = ghRequestTransformer.transformRequest(request);

        if (Helper.isEmpty(request.getProfile()) && request.getCustomModel() != null)
            throw new IllegalArgumentException("The 'profile' parameter is required when you use the `custom_model` parameter");

        PMap profileResolverHints = new PMap(request.getHints());
        profileResolverHints.putObject("profile", request.getProfile());
        profileResolverHints.putObject("has_curbsides", !request.getCurbsides().isEmpty());
        request.setProfile(profileResolver.resolveProfile(profileResolverHints));
        RouteResource.removeLegacyParameters(request.getHints());

        // Ensure elevation is enabled
        request.getHints().putObject("elevation", true);
        request.getHints().putObject(CALC_POINTS, true);
        request.getHints().putObject(INSTRUCTIONS, false);

        GHResponse ghResponse = graphHopper.route(request);

        double took = sw.stop().getMillisDouble();
        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        String logStr = infoStr + " " + request.getPoints().size() + ", took: "
                + String.format("%.1f", took) + " ms, algo: " + request.getAlgorithm() + ", profile: " + request.getProfile()
                + ", custom_model: " + request.getCustomModel();

        if (ghResponse.hasErrors()) {
            throw new MultiException(ghResponse.getErrors());
        }

        ResponsePath bestPath = ghResponse.getBest();
        if (bestPath == null || bestPath.getPoints().isEmpty()) {
            logger.warn(logStr + " - No route found");
            return Response.status(Response.Status.NOT_FOUND).
                    entity("No route found").
                    type(MediaType.APPLICATION_JSON).
                    build();
        }

        logger.info(logStr + ", distance: " + bestPath.getDistance() + ", points: " + bestPath.getPoints().size());

        ObjectNode response = buildElevationProfileResponse(bestPath, took);
        return Response.ok(response).
                header("X-GH-Took", "" + Math.round(took)).
                type(MediaType.APPLICATION_JSON).
                build();
    }

    private ObjectNode buildElevationProfileResponse(ResponsePath path, double took) {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        
        PointList points = path.getPoints();
        if (!points.is3D()) {
            throw new IllegalStateException("Route points do not contain elevation data. Ensure elevation is enabled in the route request.");
        }

        ArrayNode profileArray = json.putArray("profile");
        double cumulativeDistance = 0.0;

        if (points.size() > 0) {
            // Add first point
            ObjectNode firstPoint = JsonNodeFactory.instance.objectNode();
            firstPoint.put("distance", Helper.round(cumulativeDistance, 2));
            firstPoint.put("elevation", Helper.round2(points.getEle(0)));
            profileArray.add(firstPoint);

            // Add remaining points with cumulative distance
            for (int i = 1; i < points.size(); i++) {
                double segmentDistance = distanceCalc.calcDist(
                        points.getLat(i - 1), points.getLon(i - 1),
                        points.getLat(i), points.getLon(i)
                );
                cumulativeDistance += segmentDistance;

                ObjectNode point = JsonNodeFactory.instance.objectNode();
                point.put("distance", Helper.round(cumulativeDistance, 2));
                point.put("elevation", Helper.round2(points.getEle(i)));
                profileArray.add(point);
            }
        }

        json.put("distance", Helper.round(path.getDistance(), 3));
        json.put("ascend", Helper.round(path.getAscend(), 2));
        json.put("descend", Helper.round(path.getDescend(), 2));

        // Add info object similar to route endpoint
        ObjectNode info = JsonNodeFactory.instance.objectNode();
        info.putPOJO("copyrights", config.getCopyrights());
        info.put("took", Math.round(took));
        if (osmDate != null) {
            info.put("road_data_timestamp", osmDate);
        }
        json.putPOJO("info", info);

        return json;
    }
}

