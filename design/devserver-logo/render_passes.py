"""Render a second view and visible-part masks from the saved 3D geometry."""
import json
from pathlib import Path
import bpy
from mathutils import Vector
HERE = Path(bpy.data.filepath).resolve().parent
scene = bpy.context.scene
camera = scene.camera
old_location, old_rotation = camera.location.copy(), camera.rotation_euler.copy()
camera.location = (6, -15, 4)
camera.rotation_euler = (Vector((0,0,.4))-camera.location).to_track_quat('-Z','Y').to_euler()
scene.cycles.samples = 48
scene.render.filepath = str(HERE/'perspective-check.png')
bpy.ops.render.render(write_still=True)
camera.location, camera.rotation_euler = old_location, old_rotation

# Parts get unlit labels. Depth testing and all occlusion still come from Blender.
# No projection is inferred from the illustration or the previous hand-drawn SVG.
labels = []
def linear(v):
    return v / 12.92 if v < .04045 else ((v+.055)/1.055)**2.4
objects = sorted((o for o in scene.objects if o.type in ('MESH','CURVE')), key=lambda o:o.name)
for ob in objects:
    is_rib = any(key in ob.name for key in ('central ridge', 'left rib', 'right rib'))
    slots = ['left bevel', 'crown', 'right bevel'] if is_rib else ['surface', 'underside'] if ob.name == 'Helmet | full brim' else ['surface']
    ob.data.materials.clear()
    for slot in slots:
        i = len(labels)
        rgb = [(73+i*107)%230+15, (137+i*71)%230+15, (43+i*53)%230+15]
        mat=bpy.data.materials.new('Mask '+ob.name+' '+slot); mat.use_nodes=True
        nodes=mat.node_tree.nodes; nodes.clear()
        emit=nodes.new('ShaderNodeEmission'); emit.inputs['Color'].default_value=tuple(linear(v/255) for v in rgb)+(1,)
        out=nodes.new('ShaderNodeOutputMaterial'); mat.node_tree.links.new(emit.outputs[0],out.inputs['Surface'])
        ob.data.materials.append(mat)
        labels.append({'name':ob.name+' | '+slot,'rgb':rgb})
    if is_rib:
        for face in ob.data.polygons:
            column = face.index % 16
            face.material_index = 0 if column < 4 else 1 if column < 12 else 2
    if ob.name == 'Helmet | full brim':
        ob.modifiers['Solid brim'].material_offset = 1
        ob.modifiers['Solid brim'].material_offset_rim = 1
# A single unfiltered label sample keeps material IDs categorical. Mixing
# encoded RGB IDs at antialiased edges can invent unrelated material colors.
scene.cycles.samples=1; scene.cycles.use_denoising=False
scene.cycles.pixel_filter_type="BOX"; scene.cycles.filter_width=1.0
scene.render.filepath=str(HERE/'visible-parts.png')
bpy.ops.render.render(write_still=True)
(HERE/'visible-parts.json').write_text(json.dumps(labels,indent=2)+'\n')

# Separate geometric coverage avoids treating antialiased brand colours as part IDs.
for ob in objects:
    mat=bpy.data.materials.new('Coverage '+ob.name); mat.use_nodes=True
    nodes=mat.node_tree.nodes; nodes.clear()
    emit=nodes.new('ShaderNodeEmission')
    value=0 if ob.name.startswith('Original') else 1
    emit.inputs['Color'].default_value=(value,value,value,1)
    out=nodes.new('ShaderNodeOutputMaterial'); mat.node_tree.links.new(emit.outputs[0],out.inputs['Surface'])
    ob.data.materials.clear(); ob.data.materials.append(mat)
scene.render.filepath=str(HERE/'helmet-coverage.png')
bpy.ops.render.render(write_still=True)
