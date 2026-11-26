// JS port'unun mobil plate_test.dart ve foreign_ocr_test.dart'taki örneklerle aynı sonucu verdiğini sınar.
// Kullanım: node --test test_plate.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractPlate, extractPlateGrouped, extractRawPlate } from './plate.js';
import { readForeignPlate } from './foreign_ocr.js';

test('extractPlate: mobil örnekleri', () => {
  const cases = [
    ['34 ABC 123', '34ABC123'], ['34 A 12345', '34A12345'], ['06 AB 1234', '06AB1234'], ['35 ABC 12', '35ABC12'],
    ['34\nABC\n123', '34ABC123'], ['TR 34 ABC 123', '34ABC123'], ['TR\n34 - ABC . 123\nxx', '34ABC123'],
    ['34 ABC l23', '34ABC123'], ['O6 AB 1234', '06AB1234'], ['34 A8C 123', '34ABC123'], ['34 ABC 1S5', '34ABC155'],
    ['3L ABC 123', '31ABC123'], ['34 AB 123 Z', '34AB123'], ['34 iıİ 123', '34III123'],
    ['', null], ['MERHABA DÜNYA', null], ['99 ABC 123', null], ['12345', null], ['OO 123', null],
  ];
  for (const [inp, want] of cases) assert.equal(extractPlate(inp), want, inp);
});

test('extractPlateGrouped (öneri): grup boşluğu O/0 belirsizliğini çözer', () => {
  assert.equal(extractPlate('240ZK112'), '40ZK112'); // mobil kural: yanlış ama geçerli
  assert.equal(extractPlateGrouped('24 0ZK 112').plate, '24OZK112');
  assert.equal(extractPlateGrouped('35 KF1 715').plate, '35KFI715');
  assert.equal(extractPlateGrouped('34ABC123'), null);
});

test('extractRawPlate', () => {
  assert.equal(extractRawPlate('D\nM AB 1234'), 'MAB1234');
});

test('readForeignPlate: küçük ülke kodu soyulur, tam boy ilk harf kalır', () => {
  const box = (text, left, top, right, bottom) => ({ text, left, top, right, bottom });
  // Mavi bantta küçük "D" + plaka
  const r1 = readForeignPlate([box('D', 0, 40, 12, 58), box('M', 30, 10, 50, 60), box('AB', 60, 10, 100, 60), box('1234', 110, 10, 190, 60)]);
  assert.equal(r1.plate, 'MAB1234');
  assert.equal(r1.countryCode, 'D');
  // Tam boy "D" şehir kodu (Düsseldorf) soyulmaz
  const r2 = readForeignPlate([box('D', 0, 10, 20, 60), box('AB', 30, 10, 70, 60), box('123', 80, 10, 140, 60)]);
  assert.equal(r2.plate, 'DAB123');
});
