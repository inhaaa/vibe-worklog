import { PageRequestError } from '../transport';
export { commandReason as monthEndReason } from '../commandReason';
export { requestJson as monthEndRequest } from '../transport';

export const monthEndAccessDenied = (error: unknown): error is PageRequestError =>
  error instanceof PageRequestError && (error.status === 401 || error.status === 403 || error.reauthenticate);
