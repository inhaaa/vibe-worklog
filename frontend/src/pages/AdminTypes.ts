export interface CommandForm {
  commandId: string;
  expectedVersion?: number | null;
  reason: string;
}

export const approvalUnits = [['TEAM', '팀'], ['OFFICE', '실'], ['PASS_THROUGH', '경유 조직']] as const;

export interface Organization {
  id: string;
  code: string;
  name: string;
  parentId: string | null;
  approvalUnit?: string | null;
  active: boolean;
  version: number;
}

export interface Employee {
  id: string;
  employeeNo: string;
  displayName: string;
  joinedOn: string;
  leftOn: string | null;
  active: boolean;
  version: number;
}

export interface EmployeeForm extends CommandForm {
  employeeNo: string;
  displayName: string;
  joinedOn: string | null;
  leftOn: string | null;
  active: boolean;
}

export interface OrganizationForm extends CommandForm {
  code: string;
  name: string;
  parentId: string | null;
  approvalUnit?: string | null;
  active: boolean;
}

interface AdminModel {
  error?: string | null;
}

interface OrganizationOptions {
  options: Organization[];
  orgNames: Record<string, string>;
}

export interface EmployeesModel extends AdminModel, OrganizationOptions {
  employees: Employee[];
  employeeOrganizations: Record<string, string>;
  selectedEmployee: Employee | null;
  pageEmployeeCount: number;
  pageActiveCount: number;
  pageInactiveCount: number;
  organizationId: string | null;
  status: string;
  hasNext: boolean;
  q: string;
  page: number;
  form: EmployeeForm;
}

export interface EmployeeModel extends AdminModel, OrganizationOptions {
  employee: Employee;
  form: EmployeeForm;
  assignmentForm: CommandForm & { organizationId: string | null; validFrom: string | null };
  assignments: {
    id: string;
    organizationId: string;
    organizationName: string;
    validFrom: string;
    validTo: string | null;
    reason: string;
  }[];
}

export interface OrganizationsModel extends AdminModel, OrganizationOptions {
  organizations: Organization[];
  hasNext: boolean;
  q: string;
  page: number;
  form: OrganizationForm;
}

export interface OrganizationModel extends AdminModel, OrganizationOptions {
  organization: Organization;
  form: OrganizationForm;
}

export interface ApproverAssignment {
  id: string;
  approverEmployeeId: string;
  approverName: string;
  validFrom: string;
  validTo: string | null;
  supersededAt: string | null;
  reason: string;
  eligible: boolean;
}

export interface AccessModel extends AdminModel {
  employee: Employee;
  account: {
    id: string;
    email: string | null;
    active: boolean;
    credentialSet: boolean;
    roles: string[];
  } | null;
  accountStatus: string;
  accountState: string;
  ownAccount: boolean;
  eligibleEmployee: boolean;
  resetForm: CommandForm;
  rolesForm: CommandForm & { roles: string[] | null };
  approverForm: CommandForm & {
    employeeId: string;
    approverEmployeeId: string | null;
    validFrom: string;
  };
  assignments: ApproverAssignment[];
  currentAssignment: ApproverAssignment | null;
  candidates: Employee[];
  selectedApproverMissing: boolean;
}

export interface Template {
  id: string;
  code: string;
  name: string;
  active: boolean;
  version: number;
}

export interface TemplateVersion {
  id: string;
  templateId: string;
  revision: number;
  validFrom: string;
  reason: string;
}

export interface ScheduleDay {
  weekday: number;
  workday: boolean;
  startTime: string | null;
  endTime: string | null;
  breaks: { startTime: string; endTime: string }[];
}

export interface TemplateForm extends CommandForm {
  code: string;
  name: string;
  validFrom: string;
  days: { weekday: number; workday: boolean; startTime: string; endTime: string; breaks: string }[];
}

export interface ScheduleListModel extends AdminModel {
  templates: Template[];
  form: TemplateForm;
  today: string;
}

export interface ScheduleDetailModel extends AdminModel {
  template: Template;
  versions: TemplateVersion[];
  versionDays: Record<string, ScheduleDay[]>;
  form: TemplateForm;
  today: string;
}

export interface ScheduleAssignmentModel extends AdminModel {
  employee: Employee;
  assignments: {
    id: string;
    templateName: string;
    revision: number;
    validFrom: string;
    validTo: string | null;
    reason: string;
  }[];
  versions: TemplateVersion[];
  templateNames: Record<string, string>;
  today: string;
  form: CommandForm & { scheduleVersionId: string | null; validFrom: string };
}

export interface NetworksModel extends AdminModel {
  networks: {
    id: string;
    network: string;
    label: string;
    validFrom: string;
    validTo: string | null;
    reason: string;
    version: number;
  }[];
  pageCommand: string;
  form: CommandForm & { network: string; label: string; wideConfirmed: boolean };
}

