import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Me } from '../../core/auth/session';
import { fakeStudent, fakeTpo } from '../../../testing/fakes';
import { answerSessionProbe } from '../../../testing/session-helpers';
import { StudentHome } from './student-home';

const college = {
  id: '00000000-0000-0000-0000-0000000000c1',
  name: 'Fake Institute of Technology',
};

describe('StudentHome', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  async function render(me: Me): Promise<HTMLElement> {
    await answerSessionProbe(me);
    const fixture = TestBed.createComponent(StudentHome);
    // My submissions loads the user's letters; answer with none (fake data only).
    const list = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne('/api/submissions'),
    );
    list.flush([]);
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    return fixture.nativeElement as HTMLElement;
  }

  it('independentStudent_offersJoinMyCollege', async () => {
    const page = await render(fakeStudent);

    const join = page.querySelector<HTMLAnchorElement>('a[href="/join"]');
    expect(join?.textContent?.trim()).toBe('Join my college');
  });

  it('pendingStudent_showsRequestWaitingForTpo', async () => {
    const page = await render({ ...fakeStudent, membershipStatus: 'PENDING', college });

    expect(page.textContent).toContain(
      'Your request to join Fake Institute of Technology is waiting for your TPO.',
    );
  });

  it('approvedStudent_showsCollege', async () => {
    const page = await render({ ...fakeStudent, membershipStatus: 'APPROVED', college });

    expect(page.textContent).toContain("You're a member of Fake Institute of Technology.");
  });

  it('rejectedStudent_pointsToReasonAndNewRequest', async () => {
    const page = await render({ ...fakeStudent, membershipStatus: 'REJECTED', college });

    expect(page.textContent).toContain(
      "Your request to join Fake Institute of Technology wasn't approved.",
    );
    expect(page.querySelector('a[href="/join"]')).not.toBeNull();
  });

  it('student_offersUpload_andListsSubmissions', async () => {
    const page = await render(fakeStudent);

    expect(page.querySelector('a[href="/app/upload"]')?.textContent?.trim()).toBe(
      'Check an offer letter',
    );
    expect(page.querySelector('#submissions-heading')?.textContent).toBe('My submissions');
  });

  it('tpo_noUpload_explainsWhy', async () => {
    const page = await render(fakeTpo);

    expect(page.querySelector('a[href="/app/upload"]')).toBeNull();
    expect(page.textContent).toContain('Checking offer letters is for student accounts.');
  });

  it('tpo_showsTheirCollege', async () => {
    const page = await render(fakeTpo);

    expect(page.textContent).toContain("You're the TPO for Fake Institute of Technology.");
  });
});
