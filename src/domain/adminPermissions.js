export const adminPagePermissions = {
  'admin-home': [],
  'admin-review': ['REVIEW', 'ALLIANCE'],
  'admin-information': ['REVIEW'],
  'admin-merchants': ['REVIEW', 'USERS'],
  'admin-users': ['USERS'],
  'admin-alliance': ['ALLIANCE'],
  'admin-credit-overview': ['FINANCE'],
  'admin-credits': ['FINANCE'],
  'admin-redeem-codes': ['FINANCE'],
  'admin-plans': ['FINANCE'],
  'admin-referral': ['REFERRAL'],
  'admin-partners': ['PARTNERS'],
  'admin-orders': ['FINANCE'],
  'admin-security': ['SECURITY'],
}
export function canAdminPage(name, permissions) {
  const required = adminPagePermissions[name]
  return Array.isArray(required) && (required.length === 0 || required.some(key => permissions.includes(key)))
}
