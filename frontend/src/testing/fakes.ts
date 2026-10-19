import type { Me } from '../app/core/auth/session';

/** Fake people for tests only (CLAUDE.md: test data is fake). */
export const fakeStudent: Me = {
  userId: '00000000-0000-0000-0000-000000000001',
  name: 'Asha Test',
  email: 'asha.test@example.com',
  role: 'USER',
  college: null,
  membershipStatus: 'NONE',
};

export const fakeTpo: Me = {
  userId: '00000000-0000-0000-0000-000000000002',
  name: 'Ravi Placement',
  email: 'ravi.tpo@example.com',
  role: 'TPO',
  college: { id: '00000000-0000-0000-0000-0000000000c1', name: 'Fake Institute of Technology' },
  membershipStatus: 'NONE',
};

export const fakeAdmin: Me = {
  userId: '00000000-0000-0000-0000-000000000003',
  name: 'Admin Test',
  email: 'admin.test@example.com',
  role: 'ADMIN',
  college: null,
  membershipStatus: 'NONE',
};
