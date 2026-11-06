export interface CsrfToken {
  token: string;
  parameterName: string;
  headerName: string;
}

export interface PageEnvelope {
  page: string;
  data: Record<string, unknown>;
  csrf: CsrfToken;
}

export interface PageProps extends PageEnvelope {
  reload: () => void;
  busy?: boolean;
  onClose?: () => void;
}
