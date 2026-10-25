# Build 65: speed pass

Far terrain and the cloud deck do the same work with less of it, and draw the same picture: the same columns, faces and draw order. Every change has a switch in [FLAGS.md](../FLAGS.md) for comparing.

## Measured

Windows, RTX 2080 Ti, Ryzen 9 3900X, 1920 x 1080, Sharpest far detail, render distance 16, Sodium 0.8.13, Iris 1.8.14, stock Photon v1.3b. A fresh world, spectator mode, at the Yosemite vista (`/tp @s -10530225.5 1572 -3593832.5 -90 8`, time 6000, clear), once far terrain has settled. Flying is 25 seconds holding W from there. Build 65 and this change ran back to back in one session.

| | Build 65 | This change |
| --- | --- | --- |
| Photon, standing still | 65.3 fps | 80.0 fps |
| Photon, flying | 37.5 fps | 42.0 fps |
| No shaders, standing still | 78.2 fps | 201.5 fps |
| No shaders, flying | 39.0 fps | 54.4 fps |

Standing still is the mean of 5-second windows over 30 seconds (25 without shaders); flying is the four windows while moving. Without shaders the view was measured after the flight and a teleport back, with the columns from the flight still held. Measured fresh, before any flight, build 65 ran 93.2 fps there.

Under Photon standing still, far terrain's shading pass went from 5.9 to 2.9 ms of GPU time (the depth pre-pass stayed at 4.4 ms, and the hidden-range test takes 0.07 ms), and far terrain's own render-thread time from 7.0 to 2.6 ms. Without shaders the render thread was the limit: the cloud deck no longer shows in its profile, where it had taken most of the thread's own time, far terrain's draw went from 2.8 to 0.3 ms a frame, and far terrain's single pass from 5.6 to 4.4 ms of GPU time. The slowest 1% of frames standing still went from 27 to 22 ms under Photon and from 22 to 8 ms without shaders.

## Checked against build 65

Screenshots at the vista in worlds with the same seed (a fresh world's seed changes trees and meadows, so worlds with different seeds can't be compared pixel by pixel), two runs of build 65 and one of this change:

- Without shaders, 98.3% of pixels are identical to build 65 and 99.6% are within 2 levels in 255. Most of the 1 to 2 level differences are on far river surfaces, and come with the change, most likely from rounding now that faces' colours are read in the pixel shader. The 0.09% that differ by more than 8 levels sit on distant ridgelines and cliffs; two runs of build 65 differ by more than 8 levels in 0.12% of pixels, there too.
- Under Photon, this change differs from build 65 by a mean of 1.59/255, as two runs of build 65 differ by 1.56/255 and two shots a second apart by 1.45 to 1.63: the pack's frame-to-frame noise.

## What changed

**Cloud deck** (no shader pack; packs draw their own clouds). Every frame the deck thinned itself around high ground by going over every far column held, tens of thousands of them spread over memory: most of the render thread's own time. It now keeps its last answer while the camera, the deck and the columns are as they were, and otherwise reads a packed list of the columns' squares and tops, skipping those off its grid. `EarthCloudsTest` checks the skip changes no cell, edges touching the grid included.

**Far columns' bookkeeping.**
- Columns are found by place, in a direct-mapped table per level (`FarPlaces`), rather than by hash lookup; two held columns in one place fall back to the map. Beside each place a tag says whether the column there is meshed, so whether a level can be drawn somewhere is answered from the tables without reaching the columns. `FarPlacesTest` checks both against the map through arrivals, departures, meshes and crowded places.
- The columns waiting to remesh while hidden, and those with near cubes drawn inside them, are kept in sets of their own, so a tick and the mask no longer go over every drawn or held column for them.
- The pick reads the fade clock once, notes each drawn column's box while it has the column at hand (two in three are out of view and were reached again only to find that), and the GPU side works out where each face group starts from the counts. The pre-pass's check of the pack's Voxy code is worked out once per pack instead of every frame.

**Draws reused while the view stands** (`far.reuseDraws`). When nothing the pick depends on has changed since the last frame (the camera to the last bit, the columns, meshes and fades), each far pass draws again the multi-draws it recorded instead of going over every column to queue them.

**Looks read per pixel** (shader). A far face's look (its sprite, average colour, tint, block id and flags) was fetched by the vertex shader for each of a face's four corners and handed on. The fragment shader now reads it from the face's look index. The values are the same; the shading pass after the depth pre-pass runs the vertex shader for every face but the fragment shader only for the pixels it keeps.

**Hidden ranges left out of the shading pass** (`far.hiddenCull`, OpenGL 4.3). After the depth pre-pass, a compute pass folds the faces' depth into a pyramid of the farthest depth in each square of pixels and tests every queued range's box against it. The shading pass draws through indirect commands whose instance count that test sets. A range is left out only when its nearest point lies farther than every pixel its box covers, with room for faces reaching past their columns' edges, the pack's jitter and rounding. Over Yosemite it leaves out about half the ranges, a third of the faces. A check that drew every left-out range on its own with an occlusion query, standing still, turning and flying, found none that would have drawn a pixel: 0 samples over 12 frames, each with 8,000 to 11,500 ranges left out.

## Not done

- Precomputing the projection and view matrices' product for the far shaders changed nothing measurable.
- Under Photon the depth pre-pass spends about 2 ms on the slope depth (`earth_slopeDepth`): writing `gl_FragDepth` keeps the GPU from testing depth before shading, so every far fragment behind another is shaded too. No exact cheaper form was found; the ones measured change the picture.
- Flying is still held back by the pick walking the whole far pyramid every frame while columns arrive and fade. Re-walking only the parts that changed would be the next large step.
- Far water's three passes draw each column separately, about 5 ms of render-thread time under Photon. A shared water buffer could take out most of the per-draw state changes.
- Macs were not measured. Without OpenGL 4.3 there, the hidden-range cull stays off; the other changes apply.
