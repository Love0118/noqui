/* Execute with risky_eval after selecting the exported gadget project. */
(async () => {
    const name = globalThis.noquiGadgetName;
    if (!['hammer','gravity_suit','snow_bomb'].includes(name)) throw Error('Select one owned gadget');
    const io = require('fs');
    const output = 'E:/projects/moma/noqui/lobby-assets/authored/gadget_redesign/previews/';
    io.mkdirSync(output, {recursive:true});
    Modes.options.display.select();
    const slots = name === 'hammer' ? ['firstperson_righthand','firstperson_lefthand',
        'thirdperson_righthand','thirdperson_lefthand','gui'] :
        name === 'snow_bomb' ? ['gui','fixed','ground','firstperson_righthand','firstperson_lefthand'] : ['gui','fixed','ground'];
    for (const slot of slots) {
        DisplayMode.load(slot);
        const preview = Preview.all.find(p => p.id === 'display');
        if (slot === 'gui') {
            preview.camOrtho.zoom = 2.4;
            preview.camOrtho.updateProjectionMatrix();
            preview.controls.target.set(0,0,0);
        }
        Canvas.updateAllFaces();
        // Chromium does not repaint a covered window. screenshot() explicitly
        // renders the WebGL view on demand, avoiding a stale app screenshot.
        await new Promise(resolve => preview.screenshot({crop:slot==='gui',
            width:slot==='gui'?700:1000,height:700}, data => {
            io.writeFileSync(output+name+'_'+slot+'.png', Buffer.from(data.split(',')[1], 'base64'));
            resolve();
        }));
    }
    return {project:Project.uuid, name, captured:slots};
})()
