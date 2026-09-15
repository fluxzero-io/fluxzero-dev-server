"""Rebuild the Fluxzero construction logo with Blender 5.2 (no add-ons).

blender --background --factory-startup --python design/devserver-logo/build_model.py
"""
import argparse
import math
from pathlib import Path
import sys

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
parser = argparse.ArgumentParser()
parser.add_argument('--resolution', type=int, default=1000)
parser.add_argument('--samples', type=int, default=96)
parser.add_argument('--output', type=Path)
parser.add_argument('--variant', choices=('base', 'earmuffs', 'short-peak', 'short-peak-earmuffs'), default='short-peak-earmuffs')
opts = parser.parse_args(args)
OUTPUT = HERE if opts.variant in ("base", "short-peak-earmuffs") else HERE / "variants" / opts.variant
OUTPUT.mkdir(parents=True, exist_ok=True)
opts.output = opts.output or OUTPUT / "render.png"

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
scene = bpy.context.scene
scene.render.engine = 'CYCLES'
scene.cycles.samples = opts.samples
scene.cycles.use_denoising = True
scene.render.resolution_x = scene.render.resolution_y = opts.resolution
scene.render.resolution_percentage = 100
scene.render.film_transparent = True
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGBA'
scene.render.image_settings.color_depth = '8'
scene.view_settings.view_transform = 'Standard'
scene.view_settings.look = 'None'
scene.view_settings.exposure = 0
scene.world.color = (.65, .65, .65)
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs['Color'].default_value = (.95, .95, .95, 1)
scene.world.node_tree.nodes['Background'].inputs['Strength'].default_value = .45

def linear(v):
    return v / 12.92 if v < .04045 else ((v + .055) / 1.055) ** 2.4

def rgba(hex):
    return tuple(linear(int(hex[i:i+2], 16) / 255) for i in (0, 2, 4)) + (1,)

def material(name, color, roughness=.32, emission=False):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = rgba(color)
    bsdf.inputs['Roughness'].default_value = roughness
    bsdf.inputs['Specular IOR Level'].default_value = .28
    if emission:
        nodes = mat.node_tree.nodes
        nodes.remove(bsdf)
        emit = nodes.new('ShaderNodeEmission')
        emit.inputs['Color'].default_value = rgba(color)
        mat.node_tree.links.new(emit.outputs[0], nodes['Material Output'].inputs['Surface'])
    return mat

orange = material('Helmet | orange polymer', 'fa7100', .34)
dark = material('Mounts | graphite polymer', '303942', .35)
white = material('Original mark | white', 'ffffff', emission=True)

# Blender imports the original Bezier paths; the symbol is never redrawn.
source = ROOT / 'frontend/public/assets/fluxzero-logo.svg'
bpy.ops.import_curve.svg(filepath=str(source))
curves = list(bpy.context.selected_objects)
if not curves:
    curves = [o for o in scene.objects if o.type == 'CURVE']
# SVG importer uses metres: 90 dpi, hence 0.0254 / 90 metres per SVG unit.
# Derive this from the outer path instead of relying on the importer's unit setting.
outer = max(curves, key=lambda o: o.dimensions.x)
import_scale = outer.dimensions.x / 241.27
logo_scale = 5.3 / 241.27
for i, ob in enumerate(curves):
    is_outer = ob == outer
    ob.name = 'Original Fluxzero | blue contour' if is_outer else 'Original Fluxzero | white mark'
    for spline in ob.data.splines:
        for p in spline.bezier_points:
            for attr in ('co', 'handle_left', 'handle_right'):
                v = getattr(p, attr)
                # SVG import has inverted Y. Place the unchanged curve in the XZ plane.
                setattr(p, attr, ((v.x / import_scale - 241.27 / 2) * logo_scale,
                                 (v.y / import_scale - 263.49 / 2) * logo_scale, 0))
    ob.rotation_euler = (math.pi / 2, 0, 0)
    ob.location = (0, .05 if is_outer else .035, -.05)
    ob.data.dimensions = '2D'
    ob.data.resolution_u = 24
    ob.data.extrude = .045 if is_outer else .002
    ob.data.bevel_depth = 0
    ob.data.materials.clear()
    if not is_outer:
        ob.data.materials.append(white)
        continue
    blue = material('Original blue gradient', '3582bc', emission=True)
    nodes, links = blue.node_tree.nodes, blue.node_tree.links
    geom = nodes.new('ShaderNodeNewGeometry')
    sep = nodes.new('ShaderNodeSeparateXYZ')
    links.new(geom.outputs['Position'], sep.inputs[0])
    # Exact original SVG gradient coordinates converted to scene X/Z coordinates.
    x1, z1 = (143.23-120.635)*logo_scale, (131.745-259.88)*logo_scale-.05
    x2, z2 = (98.04-120.635)*logo_scale, (131.745-3.61)*logo_scale-.05
    dx, dz = x2-x1, z2-z1
    denom = dx*dx+dz*dz
    def mathnode(op, a, b):
        n = nodes.new('ShaderNodeMath'); n.operation = op
        for index, val in enumerate((a,b)):
            if isinstance(val, (int,float)): n.inputs[index].default_value=val
            else: links.new(val, n.inputs[index])
        return n.outputs[0]
    t = mathnode('ADD', mathnode('MULTIPLY', sep.outputs['X'], dx/denom), mathnode('MULTIPLY', sep.outputs['Z'], dz/denom))
    t = mathnode('SUBTRACT', t, (x1*dx+z1*dz)/denom)
    ramp = nodes.new('ShaderNodeValToRGB')
    ramp.color_ramp.elements[0].color = rgba('d6effb')
    ramp.color_ramp.elements[1].position = .99
    ramp.color_ramp.elements[1].color = rgba('3582bc')
    for i in range(1, 31):
        fraction = i / 31
        a, b = (214,239,251), (53,130,188)
        ramp.color_ramp.elements.new(.99*fraction).color = tuple(linear((x+(y-x)*fraction)/255) for x,y in zip(a,b))+(1,)
    links.new(t, ramp.inputs[0]); links.new(ramp.outputs['Color'], nodes['Emission'].inputs['Color'])
    ob.data.materials.append(blue)

helmet = bpy.data.objects.new('Helmet | pose (tilt and yaw)', None)
scene.collection.objects.link(helmet)
helmet.location = (0, 0, 1.50)
helmet.rotation_euler = (math.radians(-10), math.radians(-4), math.radians(8))
helmet['description'] = 'Rounded uncompressed cushions; the right ear cup is positioned against the front corner.'
RX, RY, HEIGHT = 2.8, 2.06, 2.937

def surface(u, v, offset=0):
    p = Vector((RX*math.sin(u), -RY*math.cos(u)*math.sin(v), HEIGHT*math.cos(u)*math.cos(v)))
    n = Vector((p.x/RX**2, p.y/RY**2, p.z/HEIGHT**2)).normalized()
    return p+n*offset

def mesh(name, vertices, faces, mat=orange, parent=helmet, smooth=True):
    data=bpy.data.meshes.new(name); data.from_pydata(vertices, [], faces); data.update()
    ob=bpy.data.objects.new(name,data); scene.collection.objects.link(ob)
    ob.parent=parent; ob.data.materials.append(mat)
    for face in ob.data.polygons: face.use_smooth=smooth
    return ob

# A true shell: rounded ellipsoid, open below with a thin inward wall.
nu,nv=128,128
verts=[surface(-math.pi/2 + math.pi*i/nu, -math.pi/2 + math.pi*j/nv) for i in range(nu+1) for j in range(nv+1)]
faces=[(i*(nv+1)+j,(i+1)*(nv+1)+j,(i+1)*(nv+1)+j+1,i*(nv+1)+j+1) for i in range(nu) for j in range(nv)]
shell=mesh('Helmet | shell',verts,[tuple(reversed(f)) for f in faces])
solid=shell.modifiers.new('Polymer thickness', 'SOLIDIFY'); solid.thickness=.045
# Raised longitudinal ribs follow the ellipsoid from rear to front.
for name,center,width,height in [('central ridge',0,.22,.18),('left rib',-.59,.05,.095),('right rib',.59,.05,.095)]:
    ribverts=[]; cross=16; along=96
    for j in range(along+1):
        v=-math.pi/2+math.pi*j/along
        for i in range(cross+1):
            s=i/cross
            u=center+width*(2*s-1)
            fade=1.0
            if opts.variant in ('short-peak', 'short-peak-earmuffs'):
                end=min(1.0,j/5,(along-j)/5)
                fade=end*end*(3-2*end)
            lift=.003+height*math.sin(math.pi*s)**.42*fade
            ribverts.append(surface(u,v,lift))
    ribfaces=[(j*(cross+1)+i,(j+1)*(cross+1)+i,(j+1)*(cross+1)+i+1,j*(cross+1)+i+1) for j in range(along) for i in range(cross)]
    mesh('Helmet | '+name,ribverts,ribfaces)

# Elliptical all-round brim with a gently flared lip and real thickness.
rings=8; segments=192; verts=[]
for k in range(rings+1):
    s=k/rings
    for i in range(segments):
        a=2*math.pi*i/segments
        if opts.variant in ('short-peak', 'short-peak-earmuffs'):
            front=max(0,math.cos(a))**3
            x=(2.73+.13*s)*math.sin(a)
            y=-(2.03+s*(.06+.92*front))*math.cos(a)
            z=.06-s*(.07+.58*front)+.02*math.sin(math.pi*s)
        else:
            x=(2.70+.43*s)*math.sin(a)
            y=-(2.00+.39*s+.12*s*max(0,math.cos(a)))*math.cos(a)
            z=.16-.43*s+.025*math.sin(math.pi*s)
        verts.append((x,y,z))
faces=[(k*segments+i,k*segments+(i+1)%segments,(k+1)*segments+(i+1)%segments,(k+1)*segments+i) for k in range(rings) for i in range(segments)]
brim=mesh('Helmet | full brim',verts,[tuple(reversed(f)) for f in faces])
solid=brim.modifiers.new('Solid brim', 'SOLIDIFY'); solid.thickness=.085
bevel=brim.modifiers.new('Rounded lip', 'BEVEL'); bevel.width=.035; bevel.segments=3
if opts.variant in ('short-peak', 'short-peak-earmuffs'):
    brim['description']='Tight side/rear rim; a short peak extends only at the front.'
# Both mounting tabs have identical geometry and placement, mirrored across X.
for side in (-1,1):
    u=side*1.04; v=1.27
    p=surface(u,v,.045)
    bpy.ops.mesh.primitive_cube_add(size=1)
    ob=bpy.context.object; ob.name='Mount | '+('left' if side<0 else 'right')
    ob.parent=helmet; ob.location=p
    ob.scale=(.55,.18,.65)
    ob.rotation_euler=(0,side*-.36,side*1.0)
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    bevel=ob.modifiers.new('Rounded moulding','BEVEL'); bevel.width=.065; bevel.segments=5
    ob.data.materials.append(dark)
    for f in ob.data.polygons:f.use_smooth=True


if opts.variant in ('earmuffs', 'short-peak-earmuffs'):
    earmuff_scale = 1.10
    cushion = material('Hearing protection | soft charcoal cushion', '1c232b', .72)

    def oval_box(name, dimensions, location, rotation, mat, exponent):
        depth, width, height=(value*earmuff_scale for value in dimensions)
        # A softly padded superellipse; the cushion is rounder than its housing.
        profile=[(-.5,.82),(-.45,.94),(-.28,1),(.28,1),(.45,.94),(.5,.82)]
        count=80; vertices=[]
        for axial,radial in profile:
            for i in range(count):
                angle=2*math.pi*i/count
                a,b=math.sin(angle),math.cos(angle)
                y=math.copysign(abs(a)**(2/exponent),a)*width/2*radial
                z=math.copysign(abs(b)**(2/exponent),b)*height/2*radial
                vertices.append((axial*depth,y,z))
        faces=[(k*count+i,(k+1)*count+i,(k+1)*count+(i+1)%count,k*count+(i+1)%count)
               for k in range(len(profile)-1) for i in range(count)]
        left=len(vertices); vertices.append((-depth/2,0,0))
        right=len(vertices); vertices.append((depth/2,0,0))
        last=(len(profile)-1)*count
        faces.extend((left,i,(i+1)%count) for i in range(count))
        faces.extend((right,last+(i+1)%count,last+i) for i in range(count))
        ob=mesh(name,vertices,faces,mat=mat)
        ob.location=location; ob.rotation_euler=rotation
        return ob

    def arm(name, points, radius=.065):
        data=bpy.data.curves.new(name,'CURVE'); data.dimensions='3D'
        data.resolution_u=16; data.bevel_depth=radius; data.bevel_resolution=4
        spline=data.splines.new('BEZIER'); spline.bezier_points.add(len(points)-1)
        for p,co in zip(spline.bezier_points,points):
            p.co=co; p.handle_left_type=p.handle_right_type='AUTO'
        ob=bpy.data.objects.new(name,data); scene.collection.objects.link(ob)
        ob.parent=helmet; ob.data.materials.append(dark)
        return ob

    for side in (-1,1):
        label='left' if side<0 else 'right'
        angle=math.radians(-28*side)
        normal=Vector((side*math.cos(angle),side*math.sin(angle),0))
        # The far cup is thinner and shifted inward/forward. Its unmodified
        # oval surface meets the logo corner without a cut or indentation.
        center=Vector((-3.01,.15,-1.24) if side<0 else (2.898,-.25,-1.24))
        rotation=(0,0,angle)
        pad=oval_box('Mount | earmuff cushion '+label,(.24 if side<0 else .16,1.04,1.62),center-normal*(.15*earmuff_scale),rotation,cushion,2.5)
        oval_box('Mount | earmuff housing '+label,(.38,1.00,1.56),center+normal*(.04*earmuff_scale),rotation,dark,2.8)
        oval_box('Helmet | earmuff orange cup '+label,(.40,.88,1.42),center+normal*(.22*earmuff_scale),rotation,orange,2.8)
        attachment=surface(side*1.04,1.27,.14)
        shoulder=Vector((side*3.02,-.54,.04))
        joint=center+normal*(.49*earmuff_scale)
        arm('Mount | earmuff suspension '+label,[attachment,shoulder,joint+Vector((0,0,.33*earmuff_scale)),joint],.075*earmuff_scale)
        # A flush pivot cap gives the arm a visible mechanical attachment.
        bpy.ops.mesh.primitive_uv_sphere_add(segments=24,ring_count=12,radius=1)
        pivot=bpy.context.object; pivot.name='Mount | earmuff pivot '+label
        pivot.parent=helmet; pivot.location=joint; pivot.rotation_euler=rotation
        pivot.scale=tuple(value*earmuff_scale for value in (.10,.15,.15)); pivot.data.materials.append(dark)
        for f in pivot.data.polygons:f.use_smooth=True

bpy.ops.object.camera_add(location=(0,-16,.7))
cam=bpy.context.object; cam.name='Camera | fixed orthographic logo'
cam.rotation_euler=(Vector((0,0,.7))-cam.location).to_track_quat('-Z','Y').to_euler()
cam.data.type='ORTHO'; cam.data.ortho_scale=8.8
scene.camera=cam

def light(name, pos, target, power, size, color=(1,1,1)):
    bpy.ops.object.light_add(type='AREA',location=pos)
    ob=bpy.context.object; ob.name=name; ob.data.energy=power; ob.data.shape='DISK'; ob.data.size=size; ob.data.color=color
    ob.rotation_euler=(Vector(target)-ob.location).to_track_quat('-Z','Y').to_euler()
light('Key | broad softbox',(-4,-6,7),(0,0,1),480,6)
light('Fill | right',(5,-3,3),(0,0,2),260,4)
light('Top | gloss',(1,2,7),(0,0,2),300,5)

# Record camera projection of every object for subsequent contour tracing.
bpy.context.view_layer.update()
scene['logo_variant']=opts.variant
scene['brand_source']='frontend/public/assets/fluxzero-logo.svg'
scene['brand_paths']='Original Bezier control points imported without redrawing.'
scene['svg_note']='SVG gradients approximate material shading; keep original brand paths verbatim.'
scene.render.filepath='//render.png' if opts.output == OUTPUT/'render.png' else str(opts.output.resolve())
# Open the file in its composed camera view.
for screen in bpy.data.screens:
    for area in screen.areas:
        if area.type=='VIEW_3D':
            area.spaces.active.region_3d.view_perspective='CAMERA'
            area.spaces.active.shading.type='MATERIAL'
bpy.data.orphans_purge(do_recursive=True)
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(OUTPUT/'fluxzero-helmet.blend'),compress=True)
bpy.ops.render.render(write_still=True)
print('Saved model and render:', opts.output)
