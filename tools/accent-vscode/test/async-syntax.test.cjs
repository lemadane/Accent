const assert = require('node:assert/strict');
const grammar = require('../syntaxes/accent.tmLanguage.json');
const rules = grammar.repository['accent-keywords'].patterns.map(rule => new RegExp(rule.match));
const isKeyword = text => rules.some(rule => rule.test(text));
assert.equal(isKeyword('async'), true);
assert.equal(isKeyword('await'), true);
assert.equal(isKeyword('customerAsync'), false);
assert.equal(isKeyword('customerAsync.await()'), false);
assert.equal(isKeyword('Async.run(() -> service.findRequired(id))'), false);
console.log('Async syntax regression checks passed');
assert.equal(isKeyword('async service.findRequired(id)'), true);
assert.equal(isKeyword('await ordersAsync'), true);

const modifierRules = grammar.repository['storage-modifiers'].patterns.map(rule => new RegExp(rule.match));
for (const keyword of ['public', 'protected', 'private']) {
    assert.equal(modifierRules.some(rule => rule.test(keyword)), true);
    assert.equal(modifierRules.some(rule => rule.test(keyword + 'Count')), false);
}
console.log('Visibility modifier highlighting checks passed');

const receiverRules = grammar.repository.numbers.patterns.map(rule => new RegExp(rule.match));
for (const keyword of ['this', 'super']) {
    assert.equal(receiverRules.some(rule => rule.test(keyword)), true);
}
console.log('Member qualifier highlighting checks passed');

const bindingRule = new RegExp(grammar.repository['non-null-bindings'].patterns[0].match);
for (const value of ['name!', 'this._customer!', 'super._customer!', 'myList!.add("x")']) {
    assert.equal(bindingRule.test(value), true);
}
for (const value of ['name', 'name!=null', 'not myList']) {
    assert.equal(bindingRule.test(value), false);
}
console.log('Non-null binding highlighting checks passed');

const methodRule = new RegExp(grammar.repository['non-null-methods'].patterns[0].match);
for (const value of ['name!()', 'customer!.getName!()', 'customer?.getName!()', 'super._label!()']) {
    assert.equal(methodRule.test(value), true);
}
for (const value of ['name!', 'name()', 'name!=null']) {
    assert.equal(methodRule.test(value), false);
}
console.log('Non-null method highlighting checks passed');

assert.equal(bindingRule.exec('names!!.add("John")')[0], 'names!!');
const elementRule = new RegExp(grammar.repository['non-null-bindings'].patterns[1].match);
assert.equal(elementRule.test('names![0]!'), true);
