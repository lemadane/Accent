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
for (const keyword of ['this', 'super', 'static.']) {
    assert.equal(receiverRules.some(rule => rule.test(keyword)), true);
}
console.log('Member qualifier highlighting checks passed');

const bindingRule = new RegExp(grammar.repository['nullable-bindings'].patterns[0].match);
for (const value of ['name?', 'this._customer?', 'super._customer?', 'names??']) {
    assert.equal(bindingRule.test(value), true);
}
for (const value of ['name', 'name!=null', 'names?.add(null)', 'value?:fallback']) {
    assert.equal(bindingRule.test(value), false);
}
const methodRule = new RegExp(grammar.repository['nullable-methods'].patterns[0].match);
assert.equal(methodRule.test('getName?()'), true);
assert.equal(methodRule.test('getName()'), false);
const resultRule = new RegExp(grammar.repository['nullable-bindings'].patterns[1].match);
assert.equal(resultRule.test('names.get(0)?'), true);
assert.equal(resultRule.test('names?[0]?'), true);
console.log('Nullable binding, method, and result highlighting checks passed');

assert.equal(modifierRules.some(rule => rule.test("static int count")), true);
assert.equal(modifierRules.some(rule => rule.test("static.increment()")), false);
assert.equal(receiverRules.some(rule => rule.test("static.increment()")), true);
