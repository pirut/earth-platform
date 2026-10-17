"""Earth explainer diagrams, Manim Community Edition. See README.md."""
import os
import glob
import manimpango
import numpy as np
from manim import *

PORTRAIT = os.getenv('EARTH_LAYOUT', 'landscape') == 'portrait'
OVERLAY = os.getenv('EARTH_OVERLAY', '0') == '1'
PREVIEW = os.getenv('EARTH_PREVIEW', '0') == '1'
BACKING = os.getenv('EARTH_BACKING', 'none' if OVERLAY else 'panel')
config.frame_width = 8 if PORTRAIT else 128 / 9
config.frame_height = 128 / 9 if PORTRAIT else 8
config.pixel_width = (540 if PORTRAIT else 960) if PREVIEW else (1080 if PORTRAIT else 1920)
config.pixel_height = (960 if PORTRAIT else 540) if PREVIEW else (1920 if PORTRAIT else 1080)
config.frame_rate = 30
config.background_color = '#0C1830'
YELLOW, WHITE, INK, NAVY, BLUE = '#FFDD00', '#FFFFFF', '#08080C', '#0C1830', '#2C68BE'
MUTED = '#B8C8E2'
for font_path in glob.glob(os.getenv('EARTH_FONT_DIR', '/tmp/manim-sysroot/usr/share/fonts/opentype/inter') + '/*.otf'):
    manimpango.register_font(font_path)

def label(s, size=24, color=WHITE, weight=NORMAL):
    t = Text(s, font='Inter', font_size=size, color=color, weight=weight, line_spacing=0.9)
    if OVERLAY: t.set_stroke(INK, width=3, background=True)
    return t

def cube(side=.55, color=BLUE):
    """Three vector faces; isometric illustration, not a renderer screenshot."""
    a=np.array([0,.45,0])*side; b=np.array([.8,0,0])*side
    c=np.array([0,-.45,0])*side; d=np.array([-.8,0,0])*side
    drop=np.array([0,-.85,0])*side
    return VGroup(Polygon(a,b,c,d,fill_color=color,fill_opacity=1,stroke_color=NAVY,stroke_width=1.3),
                  Polygon(d,c,c+drop,d+drop,fill_color='#244A7B',fill_opacity=1,stroke_color=NAVY,stroke_width=1.3),
                  Polygon(c,b,b+drop,c+drop,fill_color='#183458',fill_opacity=1,stroke_color=NAVY,stroke_width=1.3))

def terrain(cols=9, rows=4, scale=.45):
    out=VGroup()
    for j in range(rows-1,-1,-1):
        for i in range(cols):
            h=1+int(2.4*np.exp(-((i-cols*.55)**2+(j-rows*.55)**2)/7))
            for k in range(h):
                q=cube(scale, YELLOW if k==h-1 and h==3 else BLUE)
                q.move_to([(i-j)*.8*scale,(i+j)*.45*scale+k*.85*scale,0])
                out.add(q)
    return out.center()

def card(text, width=3.8, height=.75, color=BLUE):
    box=RoundedRectangle(width=width,height=height,corner_radius=.12,stroke_color=color,stroke_width=1.5,fill_color=NAVY,fill_opacity=.96)
    t=label(text,22)
    if t.width>width-.35:t.scale_to_fit_width(width-.35)
    return VGroup(box,t)

class EarthScene(Scene):
    def setup(self):
        self.safe_width=6.35 if PORTRAIT else 12.6
        self.center_x=-.35 if PORTRAIT else 0
        self.body_y=1 if PORTRAIT else -.1
        if OVERLAY:
            self.camera.background_opacity=0
        # Explicit navy backing means these remain readable over any gameplay.
        panel=RoundedRectangle(width=self.safe_width+.25,height=10.2 if PORTRAIT else 6.8,
                               corner_radius=.22,fill_color=NAVY,fill_opacity=.94,stroke_width=0)
        panel.move_to([self.center_x,1.1 if PORTRAIT else 0,0])
        if BACKING == 'panel': self.add(panel)
    def heading(self, number, title, subtitle):
        top=5.65 if PORTRAIT else 2.95
        kicker=label(f'EARTH PLATFORM  /  {number}',14,YELLOW,BOLD)
        title=label(title,33 if PORTRAIT else 36,WHITE,BOLD)
        if title.width>self.safe_width-.4:title.scale_to_fit_width(self.safe_width-.4)
        sub=label(subtitle,17,MUTED)
        if sub.width>self.safe_width-.4:sub.scale_to_fit_width(self.safe_width-.4)
        g=VGroup(kicker,title,sub).arrange(DOWN,buff=.16,aligned_edge=LEFT)
        g.move_to([self.center_x,top-g.height/2,0])
        g.align_to(np.array([self.center_x-self.safe_width/2+.12,0,0]),LEFT)
        self.play(FadeIn(g,shift=UP*.12),run_time=.65)
    def footer(self,text='CONCEPTUAL DIAGRAM  •  NOT TO SCALE'):
        f=label(text,12,MUTED)
        if f.width>self.safe_width-.4:f.scale_to_fit_width(self.safe_width-.4)
        f.move_to([self.center_x,-3.65 if PORTRAIT else -3.02,0]);self.play(FadeIn(f),run_time=.35)
    def finish(self): self.wait(2)

class DataToBlocks(EarthScene):
    def construct(self):
        self.heading('01','DATA BECOMES TERRAIN','Measured inputs. A playable block world.')
        stack=VGroup(*[card(t,3.6,.65) for t in ['ELEVATION','LAND COVER','CLIMATE','WATER']]).arrange(DOWN,buff=.13)
        stack.move_to([self.center_x,2.25,0] if PORTRAIT else [-4,.1,0])
        surface=VGroup()
        for j in range(5):
            for i in range(6):
                q=Square(.30,stroke_width=.7,stroke_color=NAVY,fill_opacity=1,fill_color=interpolate_color(ManimColor(BLUE),ManimColor(YELLOW),(np.sin(i*.7)*np.cos(j*.6)+1)/2))
                q.move_to([(i-2.5)*.31,(j-2)*.31,0]);surface.add(q)
        surface.scale(.78 if PORTRAIT else 1).move_to([self.center_x,-.05,0] if PORTRAIT else [0,.2,0])
        blocks=terrain().scale(.6 if PORTRAIT else .95).move_to([self.center_x,-1.95,0] if PORTRAIT else [4,.2,0])
        self.play(LaggedStart(*[FadeIn(x,shift=RIGHT*.15) for x in stack],lag_ratio=.2),run_time=1.3)
        self.play(LaggedStart(*[FadeIn(q) for q in surface],lag_ratio=.025),run_time=1)
        self.play(Indicate(stack[0],color=YELLOW),run_time=.6)
        self.play(TransformFromCopy(surface,blocks),run_time=1.6)
        tags=VGroup(label('HEIGHT SAMPLES',15,MUTED).next_to(surface,DOWN,buff=.22),label('BLOCK TERRAIN',15,YELLOW).next_to(blocks,DOWN,buff=.22))
        self.play(FadeIn(tags),run_time=.5);self.footer();self.finish()

class TrueHeight(EarthScene):
    def construct(self):
        self.heading('02','ROOM FOR REAL HEIGHT','Vertical scale stays one-to-one.')
        mountain=terrain(10,5,.5).scale(.8 if PORTRAIT else 1.05).move_to([self.center_x+.45 if PORTRAIT else self.center_x,1 if PORTRAIT else 0,0])
        self.play(LaggedStart(*[FadeIn(c,shift=UP*.15) for c in mountain],lag_ratio=.012),run_time=1.7)
        body=VGroup(Circle(radius=.12,fill_color=WHITE,fill_opacity=1,stroke_width=0),Line([0,-.12,0],[0,-.45,0],color=WHITE,stroke_width=5),Line([-.15,-.23,0],[.15,-.23,0],color=WHITE,stroke_width=4))
        body.next_to(mountain,LEFT,buff=.25);self.play(FadeIn(body),run_time=.5)
        metre=cube(.6,YELLOW).move_to([self.center_x,-1.25 if PORTRAIT else -1.8,0])
        one=label('1 BLOCK = 1 M / 3.28 FT',21,YELLOW,BOLD).next_to(metre,RIGHT,buff=.25)
        metric=VGroup(metre,one)
        if metric.width>self.safe_width-.5:metric.scale_to_fit_width(self.safe_width-.5)
        metric.move_to([self.center_x,-1.3 if PORTRAIT else -1.9,0])
        self.play(TransformFromCopy(mountain[-1],metre),FadeIn(one),run_time=1.1)
        extent=label('WORLD Y  −16,384 … 16,383',21,WHITE).move_to([self.center_x,-2.5 if PORTRAIT else -2.6,0])
        self.play(FadeIn(extent,shift=UP*.1),run_time=.6)
        self.footer('CONCEPTUAL • MAP SCALE VARIES FAR FROM THE ORIGIN');self.finish()

class FarTerrainLOD(EarthScene):
    def construct(self):
        self.heading('03','DETAIL WHERE IT MATTERS','Near blocks. Coarser voxels farther away.')
        levels=VGroup()
        for i,n in enumerate([8,4,2,1]):
            row=VGroup(*[Square(side_length=3.6/n,stroke_color=NAVY,stroke_width=2,fill_color=interpolate_color(ManimColor(YELLOW),ManimColor(BLUE),i/3),fill_opacity=1) for _ in range(n)]).arrange(RIGHT,buff=.02)
            # Constant-width strips expose the doubling, rather than imply distance bounds.
            row.stretch_to_fit_height(.55+.14*i)
            t=label(['NEAR · 1×','2×','4×','FAR · 8×'][i],18,YELLOW if i==0 else WHITE).next_to(row,LEFT,buff=.22)
            levels.add(VGroup(row,t))
        levels.arrange(DOWN,buff=.3,aligned_edge=RIGHT).scale(1 if PORTRAIT else .8).move_to([self.center_x,.8 if PORTRAIT else -.45,0])
        self.play(FadeIn(levels[0],shift=DOWN*.15),run_time=.6)
        for previous,current in zip(levels,levels[1:]):
            self.play(TransformFromCopy(previous[0],current[0]),FadeIn(current[1]),run_time=.6)
        eye=Dot(color=WHITE,radius=.08).next_to(levels,UP,buff=.5)
        rays=VGroup(DashedLine(eye.get_center(),levels[-1][0].get_corner(DL),color=MUTED,stroke_width=1),DashedLine(eye.get_center(),levels[-1][0].get_corner(DR),color=MUTED,stroke_width=1))
        self.play(FadeIn(eye),Create(rays),run_time=.8)
        note=label('Voxel size doubles by level',22,YELLOW).next_to(levels,DOWN,buff=.4)
        self.play(FadeIn(note),run_time=.5)
        self.play(Indicate(levels[0][0],color=WHITE),run_time=.8)
        self.footer('CONCEPTUAL • LEVELS AND VIEW CONE ARE ILLUSTRATIVE');self.finish()

class SharedServer(EarthScene):
    def construct(self):
        self.heading('04','ONE SHARED EARTH','The server owns the shared world.')
        server=card('AUTHORITATIVE SERVER',4.7,.9,YELLOW).move_to([self.center_x,2.4 if PORTRAIT else 1,0])
        clients=VGroup(card('PLAYER A',2.35,.8),card('PLAYER B',2.35,.8)).arrange(RIGHT,buff=.6).move_to([self.center_x,-.7 if PORTRAIT else -.85,0])
        links=VGroup(*[Line(server.get_bottom(),c.get_top(),color=BLUE,stroke_width=3) for c in clients])
        self.play(FadeIn(server),run_time=.6);self.play(Create(links),FadeIn(clients),run_time=.9)
        self.play(*[MoveAlongPath(Dot(server.get_bottom(),radius=.09,color=YELLOW),l) for l in links],run_time=1.4,rate_func=linear)
        a=cube(.4,YELLOW).next_to(clients[0],DOWN,buff=.35);b=cube(.4,YELLOW).next_to(clients[1],DOWN,buff=.35)
        self.play(FadeIn(a),FadeIn(b),run_time=.6)
        self.play(Indicate(server,color=YELLOW),run_time=.7)
        tag=label('Shared terrain. Shared changes.',23,WHITE).move_to([self.center_x,-2.6,0]);self.play(FadeIn(tag),run_time=.5)
        self.footer('CONCEPTUAL • NOT A NETWORK PACKET TRACE');self.finish()

class FairComparison(EarthScene):
    def construct(self):
        self.heading('05','DIFFERENT LAYERS','Earth generation, world height, distant rendering.')
        entries=[('Terra++','Earth generation + CubicChunks'),
                 ('Tellus','Earth generation + experimental tall worlds'),
                 ('Distant Horizons','Column-based LOD · stacked vertical spans'),
                 ('Voxy','3D voxel LOD'),
                 ('Earth Platform','Cube engine + Earth data + own far renderer')]
        rows=VGroup()
        for i,(name,role) in enumerate(entries):
            name_text=label(name,23,YELLOW if i==4 else WHITE,BOLD)
            role_text=label(role,17,MUTED)
            if PORTRAIT:
                if role_text.width>5.7:role_text.scale_to_fit_width(5.7)
                row=VGroup(name_text,role_text).arrange(DOWN,buff=.12,aligned_edge=LEFT)
            else:
                name_text.move_to([-4,0,0]);name_text.align_to(np.array([-5.6,0,0]),LEFT)
                role_text.move_to([2,0,0]);role_text.align_to(np.array([-1.8,0,0]),LEFT)
                row=VGroup(name_text,role_text)
            rows.add(row)
        rows.arrange(DOWN,buff=.37 if PORTRAIT else .42,aligned_edge=LEFT).move_to([self.center_x,.6 if PORTRAIT else -.15,0])
        for row in rows:self.play(FadeIn(row,shift=RIGHT*.18),run_time=.55)
        highlight=SurroundingRectangle(rows[-1],buff=.18,color=YELLOW,corner_radius=.08,stroke_width=1.5)
        self.play(Create(highlight),run_time=.7)
        self.footer('ARCHITECTURE OVERVIEW • NOT A PERFORMANCE RANKING');self.finish()

class HonestLimits(EarthScene):
    def construct(self):
        self.heading('06','WHAT THIS WORLD IS','Measured geography, interpreted as Minecraft.')
        items=[('REAL TERRAIN','Elevation + land cover + climate'),('PROCEDURAL DETAIL','Caves, ores, trees and structures'),('NO REAL CITIES','No recreation of real roads or buildings'),('DATA HAS LIMITS','Resolution varies. Not for navigation.')]
        group=VGroup()
        for i,(title,sub) in enumerate(items):
            num=label(f'0{i+1}',19,YELLOW,BOLD)
            txt=VGroup(label(title,23,WHITE,BOLD),label(sub,17,MUTED)).arrange(DOWN,aligned_edge=LEFT,buff=.13)
            if txt.width>5.1:txt.scale_to_fit_width(5.1)
            row=VGroup(num,txt).arrange(RIGHT,buff=.3,aligned_edge=UP)
            group.add(row)
        group.arrange(DOWN,aligned_edge=LEFT,buff=.5 if PORTRAIT else .35).move_to([self.center_x,.7 if PORTRAIT else -.1,0])
        for row in group:self.play(FadeIn(row,shift=UP*.14),run_time=.7)
        self.play(Indicate(group[2][0],color=YELLOW),run_time=.6)
        self.footer('VANILLA VILLAGES AND STRUCTURES CAN STILL GENERATE');self.finish()
