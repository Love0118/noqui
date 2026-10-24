"""Native cuboid coordinates and 1.21.11+ element rotations (stdlib only).

The format supports legacy axis/angle and modern X-then-Y-then-Z rotations.
https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11
"""
import itertools
import math

NORMALS={'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0),'south':(0,0,1),'north':(0,0,-1)}

def face_vertices(a,b,face):
    x,y,z=a;X,Y,Z=b
    return {'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],
            'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],
            'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}[face]

def rotation_angles(rotation):
    if 'axis' in rotation:
        assert rotation['axis'] in ('x','y','z') and 'angle' in rotation, 'Invalid legacy element rotation'
        return [rotation['angle'] if axis==rotation['axis'] else 0 for axis in ('x','y','z')]
    assert 'angle' not in rotation, 'Rotation angle requires an axis'
    return [rotation.get(axis,0) for axis in ('x','y','z')]

def rotate(vector,angles):
    x,y,z=vector
    for axis,angle in enumerate(angles):
        c=math.cos(math.radians(angle));s=math.sin(math.radians(angle))
        if axis==0:y,z=y*c-z*s,y*s+z*c
        elif axis==1:x,z=x*c+z*s,-x*s+z*c
        else:x,y=x*c-y*s,x*s+y*c
    return [x,y,z]

def rotated_point(element,point):
    rotation=element.get('rotation',{})
    origin=rotation.get('origin',[8,8,8])
    vector=[point[i]-origin[i] for i in range(3)]
    if rotation.get('rescale'):
        assert rotation.get('axis') in ('x','y','z'), 'Rescale requires a legacy rotation axis'
        factor=1/max(abs(math.cos(math.radians(rotation['angle']))),1e-8)
        vector=[v if i=='xyz'.index(rotation['axis']) else v*factor for i,v in enumerate(vector)]
    vector=rotate(vector,rotation_angles(rotation))
    return [vector[i]+origin[i] for i in range(3)]

def element_corners(element):
    return [rotated_point(element,p) for p in itertools.product(*zip(element['from'],element['to']))]

def rotated_normal(element,face):
    rotation=element.get('rotation',{});normal=list(NORMALS[face])
    if rotation.get('rescale'):
        factor=max(abs(math.cos(math.radians(rotation['angle']))),1e-8)
        normal=[v if i=='xyz'.index(rotation['axis']) else v*factor for i,v in enumerate(normal)]
    return rotate(normal,rotation_angles(rotation))

def model_bounds(model,fixed=False):
    points=[p for element in model['elements'] for p in element_corners(element)]
    if fixed:
        display=model['display']['fixed']
        points=[[v+display['translation'][i] for i,v in enumerate(rotate([(p[j]-8)*display['scale'][j] for j in range(3)],display['rotation']))] for p in points]
    return [[min(p[i] for p in points) for i in range(3)],[max(p[i] for p in points) for i in range(3)]]

def geometry_signature(model):
    """Texture/order/translation/uniform-scale invariant, including rotated cuboids."""
    groups=[element_corners(element) for element in model['elements']]
    points=[p for group in groups for p in group]
    low=[min(p[i] for p in points) for i in range(3)]
    scale=max(max(p[i] for p in points)-low[i] for i in range(3))
    return tuple(sorted(tuple(sorted(tuple(round((p[i]-low[i])/scale,6) for i in range(3)) for p in group)) for group in groups))
