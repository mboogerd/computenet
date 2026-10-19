import type { InputRequirement, PortType, TypeKind } from './types';

export const portColors: Record<TypeKind, string> = {
  text: '#578ad6', gitRange: '#d68534', issueSet: '#a16bd1', judgment: '#39a28b', json: '#c75d95', any: '#8998aa',
};
export const portColor = (kind?: TypeKind): string => portColors[kind ?? 'any'];
export function requirementKind(requirement?: InputRequirement): TypeKind {
  if (requirement?.kind) return requirement.kind;
  if (requirement?.fields.includes('repo')) return 'gitRange';
  if (requirement?.fields.some(field => ['added', 'removed', 'changed', 'workspace'].includes(field))) return 'issueSet';
  if (requirement?.fields.some(field => ['probability', 'reason'].includes(field))) return 'judgment';
  return requirement?.fields.every(field => ['old', 'new'].includes(field)) ? 'text' : 'any';
}
export function compatible(output?: PortType, requirement?: InputRequirement): boolean {
  if (!output || !requirement || output.kind === 'any') return true;
  if (requirement.kind && requirement.kind !== 'any' && requirement.kind !== output.kind) return false;
  const fields = new Set(['old', 'new', ...(output.extras ?? []), ...({
    gitRange: ['repo'], issueSet: ['added', 'removed', 'changed', 'workspace'], judgment: ['probability', 'reason'],
    text: [], json: [], any: [],
  }[output.kind])]);
  return output.kind === 'json' || requirement.fields.every(field => fields.has(field));
}
