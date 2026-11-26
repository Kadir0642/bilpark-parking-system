// Plaka kurallarının JS karşılığı: mobil plate_test.dart / foreign_ocr_test.dart örnekleri ve sayfanın karar kuralı.
// Kullanım: node --test kurallar.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  extractPlate, extractRawPlate, cleanPlate, isValidTrPlate, isValidForeignPlate, foreignAlnum, formatPlate, decide,
  CONFIDENCE_THRESHOLD,
} from '../js/kamera/plaka-kurallari.js';

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

test('extractPlate: düzeltme gerektirmeyen aday seçilir (mobil kuralla aynı)', () => {
  assert.equal(extractPlate('240ZK112'), '40ZK112');
  assert.equal(extractPlate('27S0760'), '27S0760');
});

test('extractRawPlate ve foreignAlnum (yabancı mod, düzeltmesiz)', () => {
  assert.equal(extractRawPlate('D\nM AB 1234'), 'MAB1234');
  assert.equal(extractRawPlate('X'), null);
  assert.equal(foreignAlnum('mü-ab 12'), 'MUAB12');
});

test('cleanPlate / doğrulama', () => {
  assert.equal(cleanPlate(' 34 abc-123 '), '34ABC123');
  assert.equal(isValidTrPlate('34 ABC 123'), true);
  assert.equal(isValidTrPlate('82 ABC 123'), false);
  assert.equal(isValidForeignPlate('MAB1234'), true);
  assert.equal(isValidForeignPlate('A'), false);
  assert.equal(isValidForeignPlate('ABCDEFGHIJKLM'), false);
});

test('formatPlate', () => {
  assert.equal(formatPlate('34ABC123'), '34 ABC 123');
  assert.equal(formatPlate('34A12345'), '34 A 12345');
  assert.equal(formatPlate('MAB1234', true), 'MAB1234');
});

test('decide: eşik 0,8 — üstünde onay, altında klavye tahminle açılır', () => {
  assert.equal(CONFIDENCE_THRESHOLD, 0.8);
  assert.deepEqual(decide({ text: '34ABC123', minConf: 0.93 }, false), { kind: 'confirm', plate: '34ABC123', guess: '34ABC123' });
  assert.deepEqual(decide({ text: '34ABC123', minConf: 0.8 }, false).kind, 'confirm');
  assert.deepEqual(decide({ text: '34ABC123', minConf: 0.79 }, false), { kind: 'edit', plate: null, guess: '34ABC123' });
  // Geçerli TR plakası çıkmazsa güven yüksek olsa bile onay sorulmaz
  assert.deepEqual(decide({ text: '99ABC123', minConf: 0.99 }, false), { kind: 'edit', plate: null, guess: '99ABC123' });
  assert.deepEqual(decide({ text: '', minConf: 0 }, false), { kind: 'none', plate: null, guess: '' });
  assert.deepEqual(decide(null, false).kind, 'none');
});

test('decide: yabancı modda TR düzeltmesi yapılmaz', () => {
  assert.deepEqual(decide({ text: 'MAB1234', minConf: 0.95 }, true), { kind: 'confirm', plate: 'MAB1234', guess: 'MAB1234' });
  assert.deepEqual(decide({ text: 'MAB1234', minConf: 0.5 }, true), { kind: 'edit', plate: null, guess: 'MAB1234' });
  // TR modunda O→0 düzeltmesi yapılır, yabancı modda yapılmaz
  assert.equal(decide({ text: 'O6AB1234', minConf: 0.9 }, false).plate, '06AB1234');
  assert.equal(decide({ text: 'O6AB1234', minConf: 0.9 }, true).plate, 'O6AB1234');
});
