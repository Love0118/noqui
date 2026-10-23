"""Compare every software-preview vertex/UV with a mesh exported from the official client.

Usage: python verify_native_skin.py path/to/native-slim-mesh.json
The mesh is produced by verification/NativeSkinMesh.java using the official 26.3 client.
This optional visual QA needs the same Pillow/NumPy dependencies as preview_assets.py.
"""
import json
import sys
from pathlib import Path
import numpy as np
from preview_assets import skin_elements, face_vertices, NORMALS

def verify(mesh):
    assert mesh['minecraftVersion']=='26.3' and mesh['model']=='slim'
    assert mesh['clientSha1']=='e877b6a07acd633fb3bb475002175cec036e7b87'
    native=mesh['faces'];elements=skin_elements()
    assert len(elements)==12 and len(native)==72
    assert {e['_part'] for e in elements}=={f['part'] for f in native}
    count=0
    for element in elements:
        for face,spec in element['faces'].items():
            reference=next(f for f in native if f['part']==element['_part'] and np.allclose(f['normal'],NORMALS[face]))
            u,v,U,V=spec['uv']
            uv=np.array([[u,v],[U,v],[U,V],[u,V]])/16
            for point,texture in zip(face_vertices(element['from'],element['to'],face),uv):
                vertex=next(v for v in reference['vertices'] if np.allclose(v[:3],point,atol=1e-6))
                assert np.allclose(texture,vertex[3:],atol=1e-6),(element['_part'],face,point)
                count+=1
    assert count==288
    return {'parts':len(elements),'faces':len(native),'verticesAndUVs':count,'source':'official Minecraft 26.3 PlayerModel'}

if __name__=='__main__':
    print(json.dumps(verify(json.loads(Path(sys.argv[1]).read_text(encoding='utf-8')))))
