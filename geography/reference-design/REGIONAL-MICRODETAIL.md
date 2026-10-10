# Regional microdetail reference direction

Date: 2026-10-09. User direction: map and elevation data establish macro geography; real photographs and regional environment inform plausible smaller details. Elevation change must affect generation. This document is an implementation direction, not a claim that every subsystem below exists.

## Evidence layers

1. Measured macro: source DEM/bathymetry with projection, datum, resolution and coverage retained. Do not move coastlines, summits or valley courses to fit a photo.
2. Measured categorical: verified land cover, and future separately licensed geology/climate inputs when present. Categorical water is not a water-surface height. Missing data stays explicit.
3. Derived terrain: source-scale slope, aspect, curvature and relative local elevation. Record derivative scale, units and uncertainty. Slope is calculated before cosmetic relief so detail does not recursively amplify itself.
4. Plausible regional detail: deterministic regional policies conditioned on those inputs. Fine rock ribs, snow drifts, vegetation placement and microrelief are generated interpretations, not surveyed objects. Version every policy in world identity.
5. Visual verification: actual Minecraft same-camera screenshots, including a wider landscape view. Scalar checks cannot establish natural appearance.

## Everest high alpine reference

Inspected actual photograph in cloud browser on 2026-10-09:
- Photograph URL: https://i0.wp.com/www.markhorrell.com/wp-content/uploads/DSCF8493.jpg?resize=720%2C540&ssl=1
- Context: https://www.markhorrell.com/blog/2021/nine-lives-by-robert-anderson-everest-from-all-angles/
- Search identifies the north face of Everest. Context article fetch timed out; image rendered successfully. Treat photographer permission as unverified and do not bundle this image as a texture or redistributed asset.

Direct visual observations: gray/tan layered rock, roughly horizontal bedding interrupted by irregular steep ribs and gullies; broad smoother snow in concave channels and below faces; thin patchy snow on ledges; exposed convex rock buttresses. This is a regional visual reference, not proof of exact geometry in our DEM preview footprint.

Primary geographic context: https://science.nasa.gov/earth/earth-observatory/exploring-mount-everests-ice-81823/ . NASA describes arêtes, cirques, glaciers, lateral moraines and rockfall debris on Himalayan ice. Its ground photograph is from Cho La pass, not our ridge; avoid relabeling nearby imagery as the exact site.

Implementation inference: retain measured large ridges; use coherent rock exposure rather than all-white snow caps. Keep snow relatively smooth and more continuous in sheltered concavity; exposed steep convex faces receive irregular rock detail. Use source slope/elevation/land-cover gates. Fine fracture/rib placement is procedural. Do not spread generic river channels or isotropic gravel noise over every high alpine surface. Geological bedding needs regional qualification; do not claim uniform specific geology from this photo alone. Glacier crevasses require glacier context, not a generic snow label.

Current preview near projected X9674928.5, Z-3112479.5, source height about 7500m. Exact matched comparison camera engine Y205, yaw69.4, pitch20 in the 256m preview window (origin7328). This remains a windowed prototype, not native full-height gameplay.

## Marin Headlands coastal reference

Primary source and directly inspected image:
- https://www.nps.gov/places/000/coastal-scrub.htm
- https://www.nps.gov/common/uploads/cropped_image/primary/F87C8282-D6DB-D226-D91783456DD3569F.jpg?mode=crop&quality=90&width=1600

NPS explains that fog, wind and salt exposure favor low scrub, with cover varying with steepness. Direct image observations: irregular exposed boulder groups, dense low gray-green shrubs and patchy flowering plants rather than an evenly spaced tree forest.

Implementation inference: coastal scrub/grass mosaics, exposure-conditioned shrub height and density, rock outcrops on steep terrain. Trees require measured cover/regional support, not a blanket plains biome. Flowers and grass color should have an explicit season; the reference photograph does not establish year-round blooms. Per-plant positions remain procedural.

## Ocean depth caution

Do not use shallow tropical reefs or Mariana seamount photographs as Challenger Deep references. Bathymetric terrain, abyssal/hadal sediment and biology require locality AND depth-qualified imagery. This catalog has not yet verified a Challenger Deep ground image; no such detail is implemented by this document.

## Acceptance criteria

- Repeatable generation across chunk boundaries, negative coordinates and load order.
- Bounded microrelief relative to source uncertainty; protected landmarks/coastline and source sample controls.
- Region and altitude materially change the policy, with explicit missing-input behavior.
- No silent addition of trees, lakes, glaciers or settlements from unrelated photos.
- Source links and measured/derived/procedural provenance remain available.
- Actual same-camera review must reduce long artificial contour steps without replacing them with random speckle.
