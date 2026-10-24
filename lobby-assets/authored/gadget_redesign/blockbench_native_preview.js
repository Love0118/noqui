/* Run with risky_eval in a new empty Generic Model project. */
(async () => {
    const io = require('fs');
    const root = 'E:/projects/moma/noqui/lobby-assets/authored/gadget_redesign/';
    if (Cube.all.length || Texture.all.length) throw Error('Use a new empty Generic Model project');
    const source = JSON.parse(io.readFileSync(root+'native_chest_preview.json', 'utf8'));
    Undo.initEdit({elements:[], textures:[], outliner:true});
    Codecs.java_block.parse(source, (root+'native_chest_preview.json').split('/').join(String.fromCharCode(92)));
    Project.name = 'Noqui native CHEST UV review';
    Canvas.updateAll();
    Undo.finishEdit('Inspect native armor UVs on inflated vanilla chest geometry', {
        elements:Cube.all.slice(), textures:Texture.all.slice(), outliner:true
    });
    await new Promise(r => setTimeout(r,300));
    if (Texture.all.some(t => t.error)) throw Error('Native armor texture failed to load');
    io.writeFileSync(root+'gravity_native_uv.bbmodel', Codecs.project.compile({bitmaps:true})+'\n');
    const preview = Preview.selected;
    for (const [name, position] of [['front',[36,26,45]],['back',[-36,26,-45]]]) {
        preview.loadAnglePreset({position, target:[0,10,0], projection:'orthographic'});
        preview.camOrtho.zoom = .65;
        preview.camOrtho.updateProjectionMatrix();
        await new Promise(resolve => preview.screenshot({crop:true,width:700,height:700}, data => {
            io.writeFileSync(root+'previews/gravity_native_blockbench_'+name+'.png', Buffer.from(data.split(',')[1],'base64'));
            resolve();
        }));
    }
    return {project:Project.uuid, cubes:Cube.all.length, texture:Texture.all[0].name, captured:['front','back']};
})()
