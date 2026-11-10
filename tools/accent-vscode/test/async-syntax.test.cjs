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
