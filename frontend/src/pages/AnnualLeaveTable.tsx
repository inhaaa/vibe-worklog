import type { ReactNode } from 'react';

export function AnnualLeaveTable({ label, columns, rows }: {
  label: string;
  columns: string[];
  rows: { id: string; cells: ReactNode[] }[];
}) {
  return <div className="table-scroll t-annual-summary-scroll">
    <table className="t-annual-summary-table" role="table" aria-label={label}>
      <thead role="rowgroup"><tr role="row">{columns.map(column =>
        <th key={column} role="columnheader" scope="col">{column}</th>)}</tr></thead>
      <tbody role="rowgroup">{rows.map(row => <tr key={row.id} role="row">
        {row.cells.map((value, i) => <td key={columns[i]} role="cell" data-label={columns[i]}>
          <div className="t-annual-summary-value">{value}</div>
        </td>)}
      </tr>)}</tbody>
    </table>
  </div>;
}
