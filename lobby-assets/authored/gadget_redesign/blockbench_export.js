/* Run inside Blockbench MCP risky_eval after create_project(java_block).
 * Set globalThis.noquiGadgetName to hammer, gravity_suit or snow_bomb first.
 * This uses Blockbench's native parser, texture loader and export codecs.
 */
(async () => {
    const name = globalThis.noquiGadgetName;
    if (!['hammer', 'gravity_suit', 'snow_bomb'].includes(name)) throw Error('Select one owned gadget');
    const root = 'E:/projects/moma/noqui/lobby-assets';
    const authored = root + '/authored/gadget_redesign';
    const target = root + '/pack/assets/noqui/models/' + name + '.json';
    const io = require('fs');
    if (Cube.all.length || Texture.all.length) throw Error('Use a new, empty Java item project');
    const source = JSON.parse(io.readFileSync(authored + '/inputs/' + name + '.json', 'utf8'));
    Undo.initEdit({elements: [], textures: [], outliner: true});
    Codecs.java_block.parse(source, target.replace(/\//g, '\\'));
    Project.name = {hammer:'Noqui Aurora Mallet', gravity_suit:'Noqui Gravity Harness', snow_bomb:'Noqui Frost Bomb'}[name];
    Canvas.updateAll();
    Undo.finishEdit('Author original Noqui gadget with dedicated textures', {
        elements: Cube.all.slice(), textures: Texture.all.slice(), outliner: true
    });
    await new Promise(resolve => setTimeout(resolve, 600));
    const invalid = Texture.all.filter(t => t.error);
    if (invalid.length) throw Error('Unresolved textures: ' + invalid.map(t => t.name).join(', '));
    const exported = JSON.parse(Codecs.java_block.compile({cube_name: true}));
    // Blockbench omits identity transforms. The parent pack requires all three
    // fixed-transform fields explicitly, so retain the native slot values.
    exported.display = Object.fromEntries(Object.entries(Project.display_settings).map(([slot, value]) =>
        [slot, {rotation:value.rotation.slice(), translation:value.translation.slice(), scale:value.scale.slice()}]));
    io.writeFileSync(target, JSON.stringify(exported, null, 2) + '\n');
    io.writeFileSync(authored + '/' + name + '.bbmodel', Codecs.project.compile({bitmaps:true}) + '\n');
    return {name, project: Project.uuid, cubes: Cube.all.length,
        textures: Texture.all.map(t => ({name: t.name, width: t.width, height: t.height, error: t.error})),
        slots: Object.keys(Project.display_settings), exported: target};
})()
