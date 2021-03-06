/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package qupath.lib.roi;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.awt.common.AwtTools;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.ROIHelpers;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.jts.ConverterJTS;
import qupath.lib.roi.interfaces.PathShape;

/**
 * A collection of static methods to help converting between PathROIs and AWT shapes, or using AWT shapes to add
 * useful functionality when using PathROIs.
 * 
 * @author Pete Bankhead
 *
 */
public class PathROIToolsAwt {

	private final static Logger logger = LoggerFactory.getLogger(PathROIToolsAwt.class);

	public enum CombineOp {ADD, SUBTRACT, INTERSECT}//, XOR}

	public static PathShape combineROIs(PathShape shape1, PathShape shape2, CombineOp op) {
		return combineROIs(shape1, shape2, op, -1);
	}

	public static PathShape combineROIs(PathShape shape1, PathShape shape2, CombineOp op, double flatness) {
		// Check we can combine
		if (!ROIHelpers.sameImagePlane(shape1, shape2))
			throw new IllegalArgumentException("Cannot combine - shapes " + shape1 + " and " + shape2 + " do not share the same image plane");
		Area area1 = getArea(shape1);
		Area area2 = getArea(shape2);
		
		// Do a quick check to see if a combination might be avoided
		if (op == CombineOp.INTERSECT) {
			if (area1.contains(area2.getBounds2D()))
				return shape2;
			if (area2.contains(area1.getBounds2D()))
				return shape1;
		} else if (op == CombineOp.ADD) {
			if (area1.contains(area2.getBounds2D()))
				return shape1;
			if (area2.contains(area1.getBounds2D()))
				return shape2;			
		}
		
		combineAreas(area1, area2, op);
		// I realise the following looks redundant... however direct use of the areas with the
		// brush tool led to strange artefacts appearing & disappearing... performing an additional
		// conversion seems to help
		//		area1 = new Area(new Path2D.Float(area1));
		// Return simplest ROI that works - prefer a rectangle or polygon over an area
		return getShapeROI(area1, shape1.getC(), shape1.getZ(), shape1.getT(), flatness);
	}
	
	/**
	 * Fill the holes of an {@link AreaROI}, or return the ROI unchanged if it contains no holes.
	 * 
	 * @param roi
	 * @return
	 */
	public static ROI fillHoles(ROI roi) {
		if (roi instanceof AreaROI)
			return removeSmallPieces((AreaROI)roi, 0, Double.POSITIVE_INFINITY);
		return roi;
	}

	/**
	 * Remove small pieces from a {@link ROI}.
	 * <p>
	 * If the ROI is an {@link AreaROI} this may be fragments that leave the ROI itself 
	 * otherwise intact.  For other ROIs, if the area is &gt; minArea it will be returned, 
	 * otherwise an empty ROI will be returned instead.
	 * 
	 * @param roi
	 * @param minArea
	 * @return
	 */
	public static ROI removeSmallPieces(ROI roi, double minArea) {
		if (roi instanceof AreaROI || roi instanceof PolygonROI)
			return removeSmallPieces((AreaROI)roi, minArea, -1);
		if (roi instanceof PathArea && ((PathArea)roi).getArea() > minArea)
			return roi;
		return ROIs.createEmptyROI();
	}

	/**
	 * Remove small pieces and fill small holes of an {@link AreaROI}.
	 * 
	 * @param roi
	 * @param minArea
	 * @param maxHoleArea
	 * @return
	 */
	public static ROI removeSmallPieces(ROI roi, double minArea, double maxHoleArea) {
		if (!roi.isArea())
			throw new IllegalArgumentException("Only PathArea ROIs supported!");
		
		// We can't have holes if we don't have an AreaROI
		if (!(roi instanceof AreaROI || roi instanceof PolygonROI)) {
			return removeSmallPieces(roi, minArea);
		}
		
		AreaROI areaROI;
		if (roi instanceof AreaROI)
			areaROI = (AreaROI)roi;
		else
			areaROI = (AreaROI)ROIs.createAreaROI(roi.getShape(), ImagePlane.getPlane(roi));
		
		var polygons = splitAreaToPolygons(areaROI);
		
		// Keep track of whether we are filtering out any pieces; if not, return the original ROI
		boolean changes = false;
		
		var path = new Path2D.Double(Path2D.WIND_NON_ZERO);
		for (var poly : polygons[1]) {
			if (minArea <= 0 || poly.getArea() > minArea) {
				var points = poly.getPolygonPoints();
				var p = points.get(0);
				path.moveTo(p.getX(), p.getY());
				for (int i = 1; i < points.size(); i++) {
					p = points.get(i);
					path.lineTo(p.getX(), p.getY());
				}
				path.closePath();
			} else
				changes = true;
		}
	
		for (var poly : polygons[0]) {
			if (maxHoleArea <= 0 || poly.getArea() > maxHoleArea) {
				var points = poly.getPolygonPoints();
				var p = points.get(0);
				path.moveTo(p.getX(), p.getY());
				for (int i = 1; i < points.size(); i++) {
					p = points.get(i);
					path.lineTo(p.getX(), p.getY());
				}
				path.closePath();
			} else
				changes = true;
		}
		if (changes)
			return getShapeROI(new Area(path), ImagePlane.getPlane(roi), 0.5);
		else
			return roi;
	}

	

	public static void combineAreas(Area area1, Area area2, CombineOp op) {
		switch (op) {
		case ADD:
			area1.add(area2);
			break;
		case SUBTRACT:
			area1.subtract(area2);
			break;
		case INTERSECT:
			area1.intersect(area2);
			break;
			//		case XOR:
			//			area1.exclusiveOr(area2);
			//			break;
		default:
			throw new IllegalArgumentException("Invalid CombineOp " + op);
		}
	}
	
	
	public static PathShape getShapeROI(Area area, ImagePlane plane, double flatness) {
		return getShapeROI(area, plane.getC(), plane.getZ(), plane.getT(), flatness);
	}


	/**
	 * Get a PathShape from an Area.
	 * This will try to return a PathRectangleROI or PathPolygonROI if possible,
	 * or PathAreaROI if neither of the other classes can adequately represent the area.
	 * 
	 * @param area
	 * @param c
	 * @param z
	 * @param t
	 * @param flatness - can be used to prefer polygons, see Shape.getPathIterator(AffineTransform at, double flatness)
	 * @return
	 */
	public static PathShape getShapeROI(Area area, int c, int z, int t, double flatness) {
		if (area.isRectangular()) {
			Rectangle2D bounds = area.getBounds2D();
			return new RectangleROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), c, z, t);
		}
		//		else if (area.isPolygonal() && area.isSingular())
		else if (area.isSingular() && (area.isPolygonal() || flatness > 0)) {
			Path2D path = new Path2D.Float(area);
			List<Point2> points = flatness > 0 ? AWTAreaROI.getLinearPathPoints(path, path.getPathIterator(null, flatness)) : AWTAreaROI.getLinearPathPoints(path, path.getPathIterator(null));
			return new PolygonROI(points, c, z, t);
			//			if (area.isPolygonal())
			//				return new PolygonROI(new Path2D.Float(area), c, z, t);
			//			else if (flatness > 0) {
			//				Path2D path = new Path2D.Float();
			//				path.append(area.getPathIterator(null, flatness), false);
			//				return new PolygonROI(path, c, z, t);
			//			}
		}
		return new AWTAreaROI(area, c, z, t);		
	}

	public static PathShape getShapeROI(Shape shape, ImagePlane plane, double flatness) {
		if (plane == null)
			plane = ImagePlane.getDefaultPlane();
		return getShapeROI(shape, plane.getC(), plane.getZ(), plane.getT(), flatness);
	}

	/**
	 * Get a PathShape from an Area.
	 * This will try to return a PathRectangleROI or PathPolygonROI if possible,
	 * or PathAreaROI if neither of the other classes can adequately represent the area.
	 * 
	 * In the input shape is an Ellipse2D then an EllipseROI will be returned.
	 * 
	 * @param shape
	 * @param c
	 * @param z
	 * @param t
	 * @param flatness - can be used to prefer polygons, see Shape.getPathIterator(AffineTransform at, double flatness)
	 * @return
	 */
	public static PathShape getShapeROI(Shape shape, int c, int z, int t, double flatness) {
		if (shape instanceof Rectangle2D) {
			Rectangle2D bounds = shape.getBounds2D();
			return new RectangleROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), c, z, t);
		}
		if (shape instanceof Ellipse2D) {
			Rectangle2D bounds = shape.getBounds2D();
			return new EllipseROI(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight(), c, z, t);
		}
		if (shape instanceof Line2D) {
			Line2D line = (Line2D)shape;
			return new LineROI(line.getX1(), line.getY1(), line.getX2(), line.getY2(), c, z, t);
		}
		// TODO: Handle Polyline! This method does not deal with 'open' PathIterators
		return getShapeROI(new Area(shape), c, z, t, flatness);
	}



	public static PathShape getShapeROI(Area area, int c, int z, int t) {
		return getShapeROI(area, c, z, t, -1);
	}




	/**
	 * Get a java.awt.Shape object representing a ROI.
	 * 
	 * @param roi
	 * @return
	 */
	public static Shape getShape(final ROI roi) {

		if (roi instanceof RectangleROI)
			return new Rectangle2D.Float((float)roi.getBoundsX(), (float)roi.getBoundsY(), (float)roi.getBoundsWidth(), (float)roi.getBoundsHeight());

		if (roi instanceof EllipseROI)
			return new Ellipse2D.Float((float)roi.getBoundsX(), (float)roi.getBoundsY(), (float)roi.getBoundsWidth(), (float)roi.getBoundsHeight());

		if (roi instanceof LineROI) {
			LineROI line = (LineROI)roi;
			return new Line2D.Float((float)line.getX1(), (float)line.getY1(), (float)line.getX2(), (float)line.getY2());
		}

		if (roi instanceof PolygonROI) {
			PolygonROI polygon = (PolygonROI)roi;
			Path2D path = new Path2D.Float();
			Vertices vertices = polygon.getVertices();
			for (int i = 0; i <  vertices.size(); i++) {
				if (i == 0)
					path.moveTo(vertices.getX(i), vertices.getY(i));
				else
					path.lineTo(vertices.getX(i), vertices.getY(i));
			}
			path.closePath();
			return path;
		}
		
		if (roi instanceof PolylineROI) {
			PolylineROI polygon = (PolylineROI)roi;
			Path2D path = new Path2D.Float();
			Vertices vertices = polygon.getVertices();
			for (int i = 0; i <  vertices.size(); i++) {
				if (i == 0)
					path.moveTo(vertices.getX(i), vertices.getY(i));
				else
					path.lineTo(vertices.getX(i), vertices.getY(i));
			}
			return path;
		}

		//		if (roi instanceof PolygonROI) {
		//			PolygonROI polygon = (PolygonROI)roi;
		//			Path2D path = new Path2D.Float();
		//			boolean firstPoint = true;
		//			for (Point2 p : polygon.getPolygonPoints()) {
		//				if (firstPoint) {
		//					path.moveTo(p.getX(), p.getY());
		//					firstPoint = false;
		//				}
		//				else {
		//					path.lineTo(p.getX(), p.getY());
		//				}
		//			}
		//			path.closePath();
		//			return path;
		//		}

		//		if (roi instanceof PolygonROI) {
		//			PolygonROI polygon = (PolygonROI)roi;
		//			VerticesIterator iterator = polygon.getVerticesIterator();
		//			Path2D path = new Path2D.Float();
		//			boolean firstPoint = true;
		//			while (iterator.hasNext()) {
		//				if (firstPoint) {
		//					path.moveTo(iterator.getX(), iterator.getY());
		//					firstPoint = false;
		//				}
		//				else {
		//					path.lineTo(iterator.getX(), iterator.getY());
		//				}
		//				iterator.next();
		//			}
		//			path.closePath();
		//			return path;
		//		}

		if (roi instanceof AWTAreaROI) {
			return ((AWTAreaROI)roi).getShape();
		} if (roi instanceof AreaROI) {
			return new AWTAreaROI((AreaROI)roi).getShape();
		}


		throw new RuntimeException(roi + " cannot be converted to a shape!");
	}


	/**
	 * Get a java.awt.geom.Area object representing a ROI.
	 * 
	 * @param roi
	 * @return
	 */
	public static Area getArea(final ROI roi) {
		Shape shape = getShape(roi);
		if (shape instanceof Area)
			return (Area)shape;
		return new Area(shape);

	}

	/**
	 * Warning: Currently, this only compares the actual shape, *not* the channel, timepoint or z-slice.
	 * However this may change in the future.
	 * A
	 * 
	 * TODO: Consider comparing channels, time-points &amp; z-slices.
	 */
	public static boolean containsShape(final Shape shape1, final Shape shape2) {
		PathIterator iterator = shape2.getPathIterator(null);
		double[] coords = new double[6];
		while (!iterator.isDone()) {
			int type = iterator.currentSegment(coords);
			if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_MOVETO)
				if (!shape1.contains(coords[0], coords[1]))
					return false;
			iterator.next();
		}
		return true;
	}


	/**
	 * Warning: Currently, this only compares the actual shape, *not* the channel, timepoint or z-slice.
	 * However this may change in the future.
	 * A
	 * 
	 * TODO: Consider comparing channels, time-points &amp; z-slices.
	 */
	public static boolean containsShape(final PathShape shape1, final PathShape shape2) {
		return containsShape(getShape(shape1), getShape(shape2));
	}

	public static List<ROI> makeTiles(final PathArea pathROI, final int tileWidth, final int tileHeight, final boolean trimToROI) {
		// Create a collection of tiles
		Rectangle bounds = AwtTools.getBounds(pathROI);
		Area area = getArea(pathROI);
		List<ROI> tiles = new ArrayList<>();
		//		int ind = 0;
		for (int y = bounds.y; y < bounds.y + bounds.height; y += tileHeight) {
			for (int x = bounds.x; x < bounds.x + bounds.width; x += tileWidth) {
				//				int width = Math.min(x + tileWidth, bounds.x + bounds.width) - x;
				//				int height = Math.min(y + tileHeight, bounds.y + bounds.height) - y;
				int width = tileWidth;
				int height = tileHeight;
				Rectangle tileBounds = new Rectangle(x, y, width, height);
				ROI tile;
				// If the tile is completely contained by the ROI, it's straightforward
				if (area.contains(x, y, width, height))
					tile = new RectangleROI(x, y, width, height);
				else if (!trimToROI) {
					// If we aren't trimming, then check if the centroid is contained
					if (area.contains(x+0.5*width, y+0.5*height))
						tile = new RectangleROI(x, y, width, height);
					else
						continue;
				}
				else {
					// Check if we are actually within the object
					if (!area.intersects(x, y, width, height))
						continue;
					// Shrink the tile if that is sensible
					// TODO: Avoid converting tiles to Areas where not essential
					Area tileArea = new Area(tileBounds);
					tileArea.intersect(area);
					if (tileArea.isEmpty())
						continue;
					if (tileArea.isRectangular()) {
						Rectangle2D bounds2 = tileArea.getBounds2D();
						tile = new RectangleROI(bounds2.getX(), bounds2.getY(), bounds2.getWidth(), bounds2.getHeight());
					}
					else
						tile = new AWTAreaROI(tileArea);
				}
				//				tile.setName("Tile " + (++ind));
				tiles.add(tile);
			}			
		}
		return tiles;
	}
	
	
	
	public static Collection<? extends ROI> computeTiledROIs(ImageData<?> imageData, PathObject parentObject, ImmutableDimension sizePreferred, ImmutableDimension sizeMax, boolean fixedSize, int overlap) {
		ROI parentROI = parentObject.getROI();
		if (parentROI == null)
			parentROI = new RectangleROI(0, 0, imageData.getServer().getWidth(), imageData.getServer().getHeight());
		return computeTiledROIs(parentROI, sizePreferred, sizeMax, fixedSize, overlap);
	}
	

	/**
	 * Create a collection of tiled ROIs corresponding to a specified parentROI if it is larger than sizeMax.
	 * 
	 * If the parentROI is smaller, it is returned as is.
	 * 
	 * @param parentROI
	 * @param sizePreferred
	 * @param sizeMax
	 * @param fixedSize
	 * @param overlap
	 * @return
	 */
	public static Collection<? extends ROI> computeTiledROIs(ROI parentROI, ImmutableDimension sizePreferred, ImmutableDimension sizeMax, boolean fixedSize, int overlap) {

		PathArea pathArea = parentROI instanceof PathArea ? (PathArea)parentROI : null;
		Rectangle2D bounds = AwtTools.getBounds2D(parentROI);
		if (pathArea == null || (bounds.getWidth() <= sizeMax.width && bounds.getHeight() <= sizeMax.height)) {
			return Collections.singletonList(parentROI);
		}


		List<ROI> pathROIs = new ArrayList<>();

		Area area = getArea(pathArea);

		double xMin = bounds.getMinX();
		double yMin = bounds.getMinY();
		int nx = (int)Math.ceil(bounds.getWidth() / sizePreferred.width);
		int ny = (int)Math.ceil(bounds.getHeight() / sizePreferred.height);
		double w = fixedSize ? sizePreferred.width : (int)Math.ceil(bounds.getWidth() / nx);
		double h = fixedSize ? sizePreferred.height : (int)Math.ceil(bounds.getHeight() / ny);

		// Center the tiles
		xMin = (int)(bounds.getCenterX() - (nx * w * .5));
		yMin = (int)(bounds.getCenterY() - (ny * h * .5));

		for (int yi = 0; yi < ny; yi++) {
			for (int xi = 0; xi < nx; xi++) {

				double x = xMin + xi * w - overlap;
				double y = yMin + yi * h - overlap;

				Rectangle2D boundsTile = new Rectangle2D.Double(x, y, w + overlap*2, h + overlap*2);

				//				double x = xMin + xi * w;
				//				double y = yMin + yi * h;
				//				
				//				Rectangle2D boundsTile = new Rectangle2D.Double(x, y, w, h);
				//					logger.info(boundsTile);
				ROI pathROI = null;
				Shape shape = getShape(pathArea);
				if (shape.contains(boundsTile))
					pathROI = new RectangleROI(boundsTile.getX(), boundsTile.getY(), boundsTile.getWidth(), boundsTile.getHeight(), parentROI.getC(), parentROI.getZ(), parentROI.getT());
				else if (pathArea instanceof RectangleROI) {
					Rectangle2D bounds2 = boundsTile.createIntersection(bounds);
					pathROI = new RectangleROI(bounds2.getX(), bounds2.getY(), bounds2.getWidth(), bounds2.getHeight(), parentROI.getC(), parentROI.getZ(), parentROI.getT());
				}
				else {
					if (!area.intersects(boundsTile))
						continue;
					Area areaTemp = new Area(boundsTile);
					areaTemp.intersect(area);
					if (!areaTemp.isEmpty())
						pathROI = new AWTAreaROI(areaTemp, parentROI.getC(), parentROI.getZ(), parentROI.getT());					
				}
				if (pathROI != null)
					pathROIs.add(pathROI);
				x += w;
			}
		}
		return pathROIs;
	}
	
	
	/**
	 * Split a multi-part ROI into separate pieces.
	 * <p>
	 * If the ROI is already a distinct, single region or line it is returned as a singleton list.
	 * 
	 * @param roi
	 * @return
	 */
	public static List<ROI> splitROI(final ROI roi) {
		if (!(roi instanceof AreaROI)) {
			return Collections.singletonList(roi);
		}
		
		var geometry = roi.getGeometry();
		var list = new ArrayList<ROI>();
		var plane = ImagePlane.getPlane(roi);
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			list.add(ConverterJTS.convertGeometryToROI(geometry.getGeometryN(i), plane));
		}
		return list;
	}
	

	/**
	 * Split Area into PolygonROIs for the exterior and the holes.
	 * <p>
	 * The first array returned gives the <i>holes</i> and the second the positive regions (admittedly, it might have 
	 * been more logical the other way around).
	 * 
	 * <pre>
	 * {@code
	 * var polygons = splitAreaToPolygons(area, -1, 0, 0);
	 * var holes = polygons[0];
	 * var regions = polygons[1];
	 * }
	 * </pre>
	 * 
	 * @param area
	 * @param c
	 * @param z
	 * @param t
	 * @return
	 */
	public static PolygonROI[][] splitAreaToPolygons(final Area area, int c, int z, int t) {

		Map<Boolean, List<PolygonROI>> map = new HashMap<>();
		map.put(Boolean.TRUE, new ArrayList<>());
		map.put(Boolean.FALSE, new ArrayList<>());

		PathIterator iter = area.getPathIterator(null, 0.5);


		List<Point2> points = new ArrayList<>();


		double areaTempSigned = 0;
		double areaCached = 0;

		double[] seg = new double[6];
		double startX = Double.NaN, startY = Double.NaN;
		double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
		boolean closed = false;
		while (!iter.isDone()) {
			switch(iter.currentSegment(seg)) {
			case PathIterator.SEG_MOVETO:
				// Log starting positions - need them again for closing the path
				startX = seg[0];
				startY = seg[1];
				x0 = startX;
				y0 = startY;
				iter.next();
				areaCached += areaTempSigned;
				areaTempSigned = 0;
				points.clear();
				points.add(new Point2(startX, startY));
				closed = false;
				continue;
			case PathIterator.SEG_CLOSE:
				x1 = startX;
				y1 = startY;
				closed = true;
				break;
			case PathIterator.SEG_LINETO:
				x1 = seg[0];
				y1 = seg[1];
				points.add(new Point2(x1, y1));
				closed = false;
				break;
			default:
				// Shouldn't happen because of flattened PathIterator
				throw new RuntimeException("Invalid area computation!");
			};
			areaTempSigned += 0.5 * (x0 * y1 - x1 * y0);
			// Add polygon if it has just been closed
			if (closed) {
				if (areaTempSigned < 0)
					map.get(Boolean.FALSE).add(new PolygonROI(points));
				else if (areaTempSigned > 0)
					map.get(Boolean.TRUE).add(new PolygonROI(points));
				// Zero indicates the shape is empty...
			}
			// Update the coordinates
			x0 = x1;
			y0 = y1;
			iter.next();
		}
		// TODO: Decide which is positive and which is negative
		areaCached += areaTempSigned;
		PolygonROI[][] polyOutput = new PolygonROI[2][];
		if (areaCached < 0) {
			polyOutput[0] = map.get(Boolean.TRUE).toArray(new PolygonROI[0]);
			polyOutput[1] = map.get(Boolean.FALSE).toArray(new PolygonROI[0]);
		} else {
			polyOutput[0] = map.get(Boolean.FALSE).toArray(new PolygonROI[0]);
			polyOutput[1] = map.get(Boolean.TRUE).toArray(new PolygonROI[0]);			
		}
		//		areaCached = Math.abs(areaCached + areaTempSigned);

		return polyOutput;
	}

	public static PolygonROI[][] splitAreaToPolygons(final AreaROI pathROI) {
		if (pathROI instanceof AWTAreaROI)
			return splitAreaToPolygons(new Area(pathROI.getShape()), pathROI.getC(), pathROI.getZ(), pathROI.getT());
		else {
			logger.debug("Converting {} to {}", pathROI, AWTAreaROI.class.getSimpleName());
			return splitAreaToPolygons((AreaROI)ROIs.createAreaROI(pathROI.getShape(), ImagePlane.getPlane(pathROI)));
		}
	}


	/**
	 * Dilate or erode a ROI using a circular structuring element.
	 * 
	 * @param roi The ROI to dilate or erode.
	 * @param radius The radius of the structuring element to use.  If positive this will be a dilation, if negative an erosion.
	 * @return
	 */
	public static PathShape roiMorphology(final ROI roi, final double radius) {
		// Much faster to use JTS...
		return (PathShape)ConverterJTS.convertGeometryToROI(roi.getGeometry().buffer(radius), ImagePlane.getPlane(roi));
//		return getShapeROI(shapeMorphology(getShape(roi), radius), roi.getC(), roi.getZ(), roi.getT());
	}

//	/**
//	 * Dilate or erode a java.awt.Shape using a circular structuring element.
//	 * 
//	 * @param shape The shape to dilate or erode.
//	 * @param radius The radius of the structuring element to use.  If positive this will be a dilation, if negative an erosion.
//	 * @return
//	 */
//	public static Area shapeMorphology(final Shape shape, double radius) {
//
//		PathIterator iterator = shape.getPathIterator(null, 0.5);
//
//		double[] coords = new double[6];
//		
//		boolean doErode = radius < 0;
//		radius = Math.abs(radius);
//
////		Path2D path = new Path2D.Double(shape);
//		Area path = new Area(shape);
//		//Rectangle2D rect = new Rectangle2D.Double()
//		RoundRectangle2D rect = new RoundRectangle2D.Double();
//		AffineTransform transform = new AffineTransform();
//		double startX = Double.NaN;
//		double startY = Double.NaN;
//		double x = Double.NaN;
//		double y = Double.NaN;
//		double x2 = Double.NaN;
//		double y2 = Double.NaN;
//		while (!iterator.isDone()) {
//
//			int type = iterator.currentSegment(coords);
//					boolean done = false;
//
//					switch(type) {
//					case PathIterator.SEG_MOVETO:
//						x2 = coords[0];
//						y2 = coords[1];
//						startX = x2;
//						startY = y2;
//						break;
//					case PathIterator.SEG_LINETO:
//						x2 = coords[0];
//						y2 = coords[1];
//
//						double length = Math.sqrt((x-x2)*(x-x2) + (y-y2)*(y-y2)) + radius*2;
//						rect.setRoundRect(-length/2, -radius, length, radius*2, radius*2, radius*2);
//						transform.setToIdentity();
//						transform.translate((x+x2)/2, (y+y2)/2);
//						transform.rotate(Math.atan2(y2-y, x2-x));
//						Area transformedRect = new Area(new Path2D.Double(rect, transform));
//						if (doErode)
//							path.subtract(transformedRect);
//						else
//							path.add(transformedRect);
//						break;
//					case PathIterator.SEG_CLOSE:
//
//						x2 = startX;
//						y2 = startY;
//
//						length = Math.sqrt((x-x2)*(x-x2) + (y-y2)*(y-y2)) + radius*2;
//						rect.setRoundRect(-length/2, -radius, length, radius*2, radius*2, radius*2);
//						transform.setToIdentity();
//						transform.translate((x+x2)/2, (y+y2)/2);
//						transform.rotate(Math.atan2(y2-y, x2-x));
//						transformedRect = new Area(new Path2D.Double(rect, transform));
//						if (doErode)
//							path.subtract(transformedRect);
//						else
//							path.add(transformedRect);
//					default:
//						break;
//					}
//
//			x = x2;
//			y = y2;
//
//			iterator.next();
//			if (done)
//				break;
//
//		}
//		
//		return path;
//	}
	
	


}