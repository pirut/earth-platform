package io.earthplatform.geography.composition;

import io.earthplatform.geography.*;
import java.util.*;

/** Finite union of interpolation-safe sample-centre footprints, including disjoint regions and dateline crossings. */
public record SafeCoverage(List<Rectangle> rectangles) {
    public static final int MAX_RECTANGLES = 1024;
    private static final Comparator<Rectangle> ORDER = Comparator.comparingDouble(Rectangle::west)
        .thenComparingDouble(Rectangle::south).thenComparingDouble(Rectangle::north).thenComparingDouble(Rectangle::eastwardSpan).thenComparingDouble(Rectangle::east);
    public SafeCoverage {
        Objects.requireNonNull(rectangles);
        if (rectangles.isEmpty() || rectangles.size()>MAX_RECTANGLES) throw new IllegalArgumentException("Invalid safe coverage rectangle count");
        var sorted=new TreeSet<>(ORDER);int count=0;
        for(Rectangle rectangle:rectangles){if(count++>=MAX_RECTANGLES)throw new IllegalArgumentException("Coverage changed beyond rectangle budget");sorted.add(Objects.requireNonNull(rectangle));}
        rectangles=List.copyOf(sorted);
    }
    public boolean covers(GeoPoint point) { for(Rectangle r:rectangles)if(r.covers(point))return true;return false; }
    public static SafeCoverage fromTiles(List<TileSpec> tiles) {
        Objects.requireNonNull(tiles);
        if(tiles.isEmpty()||tiles.size()>MAX_RECTANGLES)throw new IllegalArgumentException("Tile footprint count exceeds safe coverage budget");
        var rectangles=new ArrayList<Rectangle>(tiles.size());
        for(TileSpec t:tiles){if(rectangles.size()>=MAX_RECTANGLES)throw new IllegalArgumentException("Tile list changed beyond coverage budget");rectangles.add(new Rectangle(t.west(),t.north()-t.stepLat()*(t.height()-1),t.north(),t.stepLon()*(t.width()-1)));}
        return new SafeCoverage(rectangles);
    }
    /** Closed borders. Full longitude coverage is explicitly span=360; west==east is never overloaded. */
    public static final class Rectangle {
        private final double west,south,north,eastwardSpan,east;
        public Rectangle(double west,double south,double north,double eastwardSpan) {
            if(!Double.isFinite(west)||!Double.isFinite(south)||!Double.isFinite(north)||!Double.isFinite(eastwardSpan)
                ||south< -90||north>90||south>=north||eastwardSpan<=0||eastwardSpan>360)
                throw new IllegalArgumentException("Invalid interpolation-safe rectangle");
            // Retain both original endpoint results. Never reconstruct east by subtracting/adding a rounded span.
            this.east=GeoPoint.wrapLongitude(west+eastwardSpan);
            this.west=GeoPoint.wrapLongitude(west);
            if(eastwardSpan!=360&&this.east==this.west)throw new IllegalArgumentException("Coverage span is below representable coordinate precision");
            this.eastwardSpan=eastwardSpan;this.south=south==0?0:south;this.north=north==0?0:north;
        }
        public double west(){return west;}
        public double east(){return east;}
        public double south(){return south;}
        public double north(){return north;}
        public double eastwardSpan(){return eastwardSpan;}
        public boolean covers(GeoPoint p) {
            if(p.latitude()<south||p.latitude()>north)return false;
            if(eastwardSpan==360)return true;
            double longitude=p.longitude();
            return west<=east ? longitude>=west&&longitude<=east : longitude>=west||longitude<=east;
        }
        @Override public boolean equals(Object other){return other instanceof Rectangle r&&ORDER.compare(this,r)==0;}
        @Override public int hashCode(){return Objects.hash(west,south,north,eastwardSpan,east);}
        @Override public String toString(){return "Rectangle[west="+west+", east="+east+", south="+south+", north="+north+", span="+eastwardSpan+"]";}
    }
}
