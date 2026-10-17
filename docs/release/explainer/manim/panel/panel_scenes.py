"""Optional native 960x800 right-hand panels; original scenes remain unchanged."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from manim import *
from earth_scenes import cube, terrain, label, card, YELLOW, WHITE, BLUE, MUTED, NAVY
config.pixel_width=960
config.pixel_height=800
config.frame_width=8
config.frame_height=20/3
config.frame_rate=30
config.background_color=NAVY

class PanelScene(Scene):
    def heading(self,n,title):
        k=label(f'EARTH PLATFORM / {n} / CONCEPTUAL',13,YELLOW,BOLD)
        t=label(title,27,WHITE,BOLD)
        if t.width>7.25:t.scale_to_fit_width(7.25)
        g=VGroup(k,t).arrange(DOWN,aligned_edge=LEFT,buff=.15)
        g.move_to([-3.6,2.9,0],aligned_edge=UL)
        self.play(FadeIn(g,shift=UP*.08),run_time=.5)
    def foot(self,txt):
        x=label(txt,15,MUTED)
        if x.width>7.2:x.scale_to_fit_width(7.2)
        x.move_to([0,-2.98,0]);self.play(FadeIn(x),run_time=.3)
    def finish(self):self.wait(2)

class DataToBlocks(PanelScene):
    def construct(self):
        self.heading('01','DATA BECOMES TERRAIN')
        inp=VGroup(*[card(t,3.2,.55) for t in ['ELEVATION','LAND COVER','CLIMATE','WATER']]).arrange(DOWN,buff=.12).move_to([-1.9,.25,0])
        blocks=terrain(7,4,.4).scale(.9).move_to([1.7,.1,0])
        arrow=Arrow([-.12,.2,0],[.45,.2,0],buff=0,color=YELLOW,stroke_width=3)
        self.play(LaggedStart(*[FadeIn(x,shift=RIGHT*.1) for x in inp],lag_ratio=.2),run_time=1.5)
        self.play(Create(arrow),run_time=.5)
        self.play(LaggedStart(*[FadeIn(x,shift=UP*.12) for x in blocks],lag_ratio=.02),run_time=1.7)
        txt=label('Measured inputs → playable blocks',21,YELLOW).move_to([0,-2.1,0]);self.play(FadeIn(txt),run_time=.5)
        self.foot('Source resolution varies. Blocks add procedural detail.');self.finish()

class TrueHeight(PanelScene):
    def construct(self):
        self.heading('02','BEYOND THE VANILLA HEIGHT')
        # Vanilla Overworld has buildable Y -64..319; 320 is the upper boundary.
        boundary=DashedLine([-3.55,.7,0],[3.55,.7,0],dash_length=.09,color=MUTED,stroke_width=2)
        cap=label('Y 320 · upper boundary',16,WHITE).move_to([-2.15,1.05,0])
        column=Rectangle(width=1.35,height=1.9,color=BLUE,fill_color=BLUE,fill_opacity=.22).move_to([-2.55,-.25,0])
        coltxt=VGroup(label('VANILLA',17,WHITE,BOLD),label('384 layers',17,MUTED)).arrange(DOWN,buff=.15).move_to(column)
        lower=label('Y −64',16,MUTED).next_to(column,DOWN,buff=.12)
        self.play(Create(column),FadeIn(coltxt),FadeIn(lower),run_time=.6)
        self.play(Create(boundary),FadeIn(cap),run_time=.6)
        cubes=VGroup()
        for row,y in enumerate([-1.02,-.3,.42,1.14]):
            for col,x in enumerate([.55,1.45,2.35]):
                if row==1 and col==1:continue
                q=cube(.36,YELLOW if row in [1,2] else BLUE).move_to([x,y,0]);cubes.add(q)
        self.play(LaggedStart(*[FadeIn(q,shift=UP*.12) for q in cubes],lag_ratio=.08),run_time=1.4)
        player=VGroup(Circle(radius=.10,fill_color=WHITE,fill_opacity=1,stroke_width=0),Line([0,-.12,0],[0,-.39,0],color=WHITE,stroke_width=4),Line([-.13,-.22,0],[.13,-.22,0],color=WHITE,stroke_width=3)).move_to([1.45,-.3,0])
        neighborhood=DashedVMobject(Ellipse(width=2.95,height=2.6,color=YELLOW,stroke_width=1.6).move_to([1.45,-.08,0]),num_dashes=40)
        top=label('LOAD AROUND THE PLAYER',15,YELLOW,BOLD).move_to([1.4,1.72,0])
        self.play(FadeIn(player),Create(neighborhood),FadeIn(top),run_time=.7)
        size=label('Independent 16 × 16 × 16 cubes',18,YELLOW).move_to([1.3,-1.65,0])
        self.play(FadeIn(size),run_time=.5)
        extent=label('EARTH Y  −16,384 … 16,383',21,WHITE).move_to([0,-2.2,0])
        self.play(FadeIn(extent),run_time=.4)
        self.foot('VERTICAL SCALE: 1 BLOCK = 1 M / 3.28 FT  ·  NOT TO SCALE');self.finish()

class FarTerrainLOD(PanelScene):
    def construct(self):
        self.heading('03','DETAIL WHERE IT MATTERS')
        levels=VGroup()
        for i,n in enumerate([8,4,2,1]):
            row=VGroup(*[Square(side_length=3.5/n,stroke_color=NAVY,stroke_width=2,fill_color=interpolate_color(ManimColor(YELLOW),ManimColor(BLUE),i/3),fill_opacity=1) for _ in range(n)]).arrange(RIGHT,buff=.02).stretch_to_fit_height(.45)
            t=label(['NEAR · 1×','2×','4×','FAR · 8×'][i],19,WHITE).next_to(row,LEFT,buff=.25)
            levels.add(VGroup(row,t))
        levels.arrange(DOWN,buff=.25,aligned_edge=RIGHT).move_to([0,0,0])
        self.play(FadeIn(levels[0]),run_time=.6)
        for a,b in zip(levels,levels[1:]):self.play(TransformFromCopy(a[0],b[0]),FadeIn(b[1]),run_time=.7)
        txt=label('Voxel size doubles by level',23,YELLOW).move_to([0,-2.1,0]);self.play(FadeIn(txt),run_time=.5)
        self.foot('Illustrative levels. Farther terrain uses coarser detail.');self.finish()

class SharedServer(PanelScene):
    def construct(self):
        self.heading('04','ONE SHARED EARTH')
        server=card('AUTHORITATIVE SERVER',5,.75,YELLOW).move_to([0,1.1,0])
        clients=VGroup(card('PLAYER A',2.6,.7),card('PLAYER B',2.6,.7)).arrange(RIGHT,buff=.7).move_to([0,-1,0])
        links=VGroup(*[Line(server.get_bottom(),c.get_top(),color=BLUE,stroke_width=3) for c in clients])
        self.play(FadeIn(server),run_time=.6);self.play(Create(links),FadeIn(clients),run_time=.8)
        self.play(*[MoveAlongPath(Dot(server.get_bottom(),radius=.08,color=YELLOW),l) for l in links],run_time=1.3)
        self.play(FadeIn(label('Shared terrain. Shared changes.',22,YELLOW).move_to([0,-2.1,0])),run_time=.5)
        self.foot('Conceptual architecture, not a network packet trace.');self.finish()

class FairComparison(PanelScene):
    def construct(self):
        self.heading('05','DIFFERENT LAYERS')
        entries=[('Terra++','Earth generation\n+ CubicChunks'),('Tellus','Earth generation\n+ experimental tall worlds'),('Distant Horizons','Column-based LOD\n+ stacked vertical spans'),('Voxy','3D voxel LOD'),('Earth Platform','Cube engine + Earth data\n+ own far renderer')]
        rows=VGroup()
        for i,(name,role) in enumerate(entries):
            a=label(name,19,YELLOW if i==4 else WHITE,BOLD);a.move_to([-3.45,0,0],aligned_edge=LEFT)
            b=label(role,17,MUTED);b.move_to([-.65,0,0],aligned_edge=LEFT)
            rows.add(VGroup(a,b))
        rows.arrange(DOWN,aligned_edge=LEFT,buff=.24).move_to([0,-.15,0])
        for r in rows:self.play(FadeIn(r,shift=RIGHT*.1),run_time=.55)
        self.play(Create(SurroundingRectangle(rows[-1],buff=.12,color=YELLOW,stroke_width=1.5)),run_time=.6)
        self.foot('Architecture overview. Not a performance ranking.');self.finish()

class HonestLimits(PanelScene):
    def construct(self):
        self.heading('06','WHAT THIS WORLD IS')
        rows=VGroup()
        for i,(a,b) in enumerate([('REAL TERRAIN','Elevation + land cover + climate'),('PROCEDURAL DETAIL','Caves, ores, trees and structures'),('NO REAL CITIES','No real roads or buildings recreated'),('DATA HAS LIMITS','Resolution varies. Not for navigation.')]):
            num=label(f'0{i+1}',18,YELLOW,BOLD)
            tx=VGroup(label(a,21,WHITE,BOLD),label(b,17,MUTED)).arrange(DOWN,aligned_edge=LEFT,buff=.09)
            rows.add(VGroup(num,tx).arrange(RIGHT,buff=.25,aligned_edge=UP))
        rows.arrange(DOWN,buff=.3,aligned_edge=LEFT).move_to([0,-.15,0])
        for row in rows:self.play(FadeIn(row,shift=UP*.1),run_time=.65)
        self.foot('Vanilla villages and structures can still generate.');self.finish()
