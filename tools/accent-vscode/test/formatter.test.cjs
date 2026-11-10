const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.join(__dirname, '../src/extension.ts'), 'utf8');
const context = {
    exports: {},
    require: name => name === 'vscode'
        ? { Range: class {}, TextEdit: { replace: (_, text) => text } }
        : name === 'vscode-languageclient/node' ? {} : require(name)
};
vm.createContext(context);
vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS }), context);
function format(text) {
    context.document = { getText: () => text, positionAt: offset => offset };
    return vm.runInContext('formatAccentDocument(document, { tabSize: 2, insertSpaces: true })[0]', context) ?? text;
}
for (const eol of ['\n', '\r\n']) {
    for (const ending of ['', eol, eol + eol]) {
        const text = 'class X {}' + ending;
        assert.equal(format(text), text);
        assert.equal(format(format(text)), text);
    }
    for (const delimiter of ['"""', '`', '$"""', '$`']) {
        const close = delimiter.replace('$', '');
        const text = [
            'class X {',
            `  String s = ${delimiter}`,
            '      hello   world  ',
            '',
            '        { content }',
            `      ${close};`,
            '  void run() {}',
            '}', ''
        ].join(eol);
        assert.equal(format(text), text);
        assert.equal(format(format(text)), text);
    }
}
console.log('Formatter regression checks passed');

const marked = [
    'class Sample {',
    '  private String _name = "John";',
    '  void run(List<String> myList) {',
    '    if (not myList) { this._name = $"Hello {this._name}"; }',
    '  }',
    '}', ''
].join('\n');
assert.equal(format(marked), marked);
assert.equal(format(format(marked)), marked);
console.log('Non-null binding formatting checks passed');

const markedMethods = [
    'class Sample {',
    '  String name() { return "John"; }',
    '  String read!(Sample customer) {',
    '    return customer?.name() else "Unknown";',
    '  }',
    '}', ''
].join('\n');
assert.equal(format(markedMethods), markedMethods);
assert.equal(format(format(markedMethods)), markedMethods);
console.log('Non-null method formatting checks passed');

const elementContracts = [
    'class Sample {',
    '  void run(List<String> names, String[] values) {',
    '    names.add(values[0]);',
    '  }',
    '}', ''
].join('\n');
assert.equal(format(elementContracts), elementContracts);
assert.equal(format(format(elementContracts)), elementContracts);
