const STATUS_CLASS = {
  COMPLETED: 'badge-green',
  PENDING: 'badge-yellow',
  PROCESSING: 'badge-yellow',
  FAILED: 'badge-orange',
  DEAD_LETTER: 'badge-red',
};

function StatusBadge({ status }) {
  const className = STATUS_CLASS[status] ?? 'badge-gray';
  return <span className={`badge ${className}`}>{status}</span>;
}

export default StatusBadge;
