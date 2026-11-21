// Match OrgCommands.requiredText and Java String.strip(), including its treatment of non-breaking spaces.
const whitespace = '[\\u0009-\\u000d\\u001c-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200a\\u2028\\u2029\\u205f\\u3000]';
const reasonEdges = new RegExp('^' + whitespace + '+|' + whitespace + '+$', 'gu');

export function commandReason(reason: string) {
  const trimmed = reason.replace(reasonEdges, '');
  const length = Array.from(trimmed).length;
  const forbiddenCharacters = /[\p{Cc}\p{Cs}]/u.test(trimmed);
  return { trimmed, length, forbiddenCharacters, validReason: length >= 1 && length <= 500 && !forbiddenCharacters };
}
