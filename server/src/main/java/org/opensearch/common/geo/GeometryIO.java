/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.common.geo;

import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.unit.DistanceUnit;
import org.opensearch.geometry.Circle;
import org.opensearch.geometry.Geometry;
import org.opensearch.geometry.GeometryCollection;
import org.opensearch.geometry.GeometryVisitor;
import org.opensearch.geometry.Line;
import org.opensearch.geometry.LinearRing;
import org.opensearch.geometry.MultiLine;
import org.opensearch.geometry.MultiPoint;
import org.opensearch.geometry.MultiPolygon;
import org.opensearch.geometry.Point;
import org.opensearch.geometry.Polygon;
import org.opensearch.geometry.Rectangle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for binary serializtion/deserialization of libs/geo classes
 */
public final class GeometryIO {

    public static void writeGeometry(StreamOutput out, Geometry geometry) throws IOException {
        out.writeString(GeoJson.getGeoJsonName(geometry).toLowerCase(Locale.ROOT));
        geometry.visit(new GeometryVisitor<Void, IOException>() {
            @Override
            public Void visit(Circle circle) throws IOException {
                writeCoordinate(circle.getLat(), circle.getLon(), circle.getAlt());
                out.writeDouble(circle.getRadiusMeters());
                DistanceUnit.METERS.writeTo(out);
                return null;
            }

            @Override
            public Void visit(GeometryCollection<?> collection) throws IOException {
                out.writeVInt(collection.size());
                for (Geometry shape : collection) {
                    writeGeometry(out, shape);
                }
                return null;
            }

            @Override
            public Void visit(Line line) throws IOException {
                writeCoordinates(line);
                return null;
            }

            @Override
            public Void visit(LinearRing ring) {
                throw new UnsupportedOperationException("linear ring is not supported");
            }

            @Override
            public Void visit(MultiLine multiLine) throws IOException {
                out.writeVInt(multiLine.size());
                for (Line line : multiLine) {
                    visit(line);
                }
                return null;
            }

            @Override
            public Void visit(MultiPoint multiPoint) throws IOException {
                out.writeVInt(multiPoint.size());
                for (int i = 0; i < multiPoint.size(); i++) {
                    Point point = multiPoint.get(i);
                    writeCoordinate(point.getY(), point.getX(), point.getZ());
                }
                return null;
            }

            @Override
            public Void visit(MultiPolygon multiPolygon) throws IOException {
                out.writeBoolean(true); // Orientation for BWC with ShapeBuilder
                out.writeVInt(multiPolygon.size());
                for (int i = 0; i < multiPolygon.size(); i++) {
                    visit(multiPolygon.get(i));
                }
                return null;
            }

            @Override
            public Void visit(Point point) throws IOException {
                out.writeVInt(1); // Number of points For BWC with Shape Builder
                writeCoordinate(point.getY(), point.getX(), point.getZ());
                return null;
            }

            @Override
            public Void visit(Polygon polygon) throws IOException {
                writeCoordinates(polygon.getPolygon());
                out.writeBoolean(true); // Orientation for BWC with ShapeBuilder
                out.writeVInt(polygon.getNumberOfHoles());
                for (int i = 0; i < polygon.getNumberOfHoles(); i++) {
                    writeCoordinates(polygon.getHole(i));
                }
                return null;
            }

            @Override
            public Void visit(Rectangle rectangle) throws IOException {
                writeCoordinate(rectangle.getMaxY(), rectangle.getMinX(), rectangle.getMinZ()); // top left
                writeCoordinate(rectangle.getMinY(), rectangle.getMaxX(), rectangle.getMaxZ()); // bottom right
                return null;
            }

            private void writeCoordinate(double lat, double lon, double alt) throws IOException {
                out.writeDouble(lon);
                out.writeDouble(lat);
                out.writeOptionalDouble(Double.isNaN(alt) ? null : alt);
            }

            private void writeCoordinates(Line line) throws IOException {
                out.writeVInt(line.length());
                for (int i = 0; i < line.length(); i++) {
                    writeCoordinate(line.getY(i), line.getX(i), line.getZ(i));
                }
            }

        });
    }

    public static Geometry readGeometry(StreamInput in) throws IOException {
        String type = in.readString();
        switch (type) {
            case "geometrycollection":
                return readGeometryCollection(in);
            case "polygon":
                return readPolygon(in);
            case "point":
                return readPoint(in);
            case "linestring":
                return readLine(in);
            case "multilinestring":
                return readMultiLine(in);
            case "multipoint":
                return readMultiPoint(in);
            case "multipolygon":
                return readMultiPolygon(in);
            case "envelope":
                return readRectangle(in);
            case "circle":
                return readCircle(in);
            default:
                throw new UnsupportedOperationException("unsupported shape type " + type);
        }
    }

    private static GeometryCollection<Geometry> readGeometryCollection(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<Geometry> shapes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            shapes.add(readGeometry(in));
        }
        return new GeometryCollection<>(shapes);
    }

    private static Polygon readPolygon(StreamInput in) throws IOException {
        double[][] shellComponents = readLineComponents(in);
        boolean orientation = in.readBoolean();
        LinearRing shell = buildLinearRing(shellComponents, orientation);
        int numberOfHoles = in.readVInt();
        if (numberOfHoles > 0) {
            List<LinearRing> holes = new ArrayList<>(numberOfHoles);
            for (int i = 0; i < numberOfHoles; i++) {
                holes.add(buildLinearRing(readLineComponents(in), orientation));
            }
            return new Polygon(shell, holes);
        } else {
            return new Polygon(shell);
        }
    }

    private static double[][] readLineComponents(StreamInput in) throws IOException {
        int len = in.readVInt();
        double[] lat = new double[len];
        double[] lon = new double[len];
        double[] alt = new double[len];
        for (int i = 0; i < len; i++) {
            lon[i] = in.readDouble();
            lat[i] = in.readDouble();
            alt[i] = readAlt(in);
        }
        if (Double.isNaN(alt[0])) {
            return new double[][]{lat, lon};
        } else {
            return new double[][]{lat, lon, alt};
        }
    }

    private static void reverse(double[][] arr) {
        for (double[] carr : arr) {
            int len = carr.length;
            for (int j = 0; j < len / 2; j++) {
                double temp = carr[j];
                carr[j] = carr[len - j - 1];
                carr[len - j - 1] = temp;
            }
        }
    }

    private static LinearRing buildLinearRing(double[][] arr, boolean orientation) {
        if (orientation == false) {
            reverse(arr);
        }
        if (arr.length == 3) {
            return new LinearRing(arr[1], arr[0], arr[2]);
        } else {
            return new LinearRing(arr[1], arr[0]);
        }
    }

    private static Point readPoint(StreamInput in) throws IOException {
        int size = in.readVInt(); // For BWC with Shape Builder
        if (size != 1) {
            throw new IOException("Unexpected point count " + size);
        }
        double lon = in.readDouble();
        double lat = in.readDouble();
        double alt = readAlt(in);
        return new Point(lon, lat, alt);
    }

    private static Line readLine(StreamInput in) throws IOException {
        double[][] coords = readLineComponents(in);
        if (coords.length == 3) {
            return new Line(coords[1], coords[0], coords[2]);
        } else {
            return new Line(coords[1], coords[0]);
        }
    }

    private static MultiLine readMultiLine(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<Line> lines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lines.add(readLine(in));
        }
        return new MultiLine(lines);
    }

    private static MultiPoint readMultiPoint(StreamInput in) throws IOException {
        int size = in.readVInt();
        List<Point> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double lon = in.readDouble();
            double lat = in.readDouble();
            double alt = readAlt(in);
            points.add(new Point(lon, lat, alt));
        }
        return new MultiPoint(points);
    }


    private static MultiPolygon readMultiPolygon(StreamInput in) throws IOException {
        in.readBoolean(); // orientation for BWC
        int size = in.readVInt();
        List<Polygon> polygons = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            polygons.add(readPolygon(in));
        }
        return new MultiPolygon(polygons);
    }

    private static Rectangle readRectangle(StreamInput in) throws IOException {
        // top left
        double minLon = in.readDouble();
        double maxLat = in.readDouble();
        double minAlt = readAlt(in);

        // bottom right
        double maxLon = in.readDouble();
        double minLat = in.readDouble();
        double maxAlt = readAlt(in);

        return new Rectangle(minLon, maxLon, maxLat, minLat, minAlt, maxAlt);
    }

    private static double readAlt(StreamInput in) throws IOException {
        Double alt = in.readOptionalDouble();
        if (alt == null) {
            return Double.NaN;
        } else {
            return alt;
        }
    }

    private static Circle readCircle(StreamInput in) throws IOException {
        double lon = in.readDouble();
        double lat = in.readDouble();
        double alt = readAlt(in);
        double radius = in.readDouble();
        DistanceUnit distanceUnit = DistanceUnit.readFromStream(in);
        return new Circle(lon, lat, alt, distanceUnit.toMeters(radius));
    }
}
