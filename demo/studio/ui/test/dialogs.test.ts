import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { validateWorkflowName } from '../src/workflows';

function nativeDialogCalls(source: string): string[] {
  const file = ts.createSourceFile('source.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const found: string[] = [];
  const dialogs = new Set(['prompt', 'confirm', 'alert']);
  const globals = new Set(['window', 'globalThis', 'self']);
  const visit = (node: ts.Node) => {
    if (ts.isCallExpression(node)) {
      const target = node.expression;
      if (ts.isIdentifier(target) && dialogs.has(target.text)) found.push(target.text);
      if (ts.isPropertyAccessExpression(target) && ts.isIdentifier(target.expression)
        && globals.has(target.expression.text) && dialogs.has(target.name.text)) found.push(target.getText(file));
      if (ts.isElementAccessExpression(target) && ts.isIdentifier(target.expression)
        && globals.has(target.expression.text) && ts.isStringLiteral(target.argumentExpression)
        && dialogs.has(target.argumentExpression.text)) found.push(target.getText(file));
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return found;
}

describe('in-page dialogs', () => {
  it('forbids native browser dialog calls throughout src', () => {
    const sources = import.meta.glob('../src/**/*', { query: '?raw', import: 'default', eager: true });
    expect(Object.keys(sources).length).toBeGreaterThan(0);
    const violations = Object.entries(sources).flatMap(([path, source]) =>
      nativeDialogCalls(String(source)).map(call => `${path}: ${call}`));
    expect(violations).toEqual([]);
  });
  it('detects bare and qualified calls while ignoring strings and non-global methods', () => {
    expect(nativeDialogCalls(`prompt('name'); confirm('sure'); alert('error'); window.confirm('sure');
      globalThis.prompt('name'); self['alert']('error');`)).toHaveLength(6);
    expect(nativeDialogCalls(`const text = "prompt('name')"; service.confirm(); // alert('error')`)).toEqual([]);
  });
});

describe('workflow name validation', () => {
  it.each(['', ' ', '\t\n', 'x'.repeat(81), ` ${'x'.repeat(81)} `])('rejects invalid name %j', value => {
    expect(validateWorkflowName(value)).toBe('Workflow name must be 1–80 characters.');
  });
  it.each(['x', 'x'.repeat(80), '  New workflow  ', `\t${'x'.repeat(80)}\n`])('accepts trimmed name %j', value => {
    expect(validateWorkflowName(value)).toBeNull();
  });
});
