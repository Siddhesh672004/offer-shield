# Known companies seed list: V-21 research (2026-11-18)

The machine-readable body is in `known-companies.json` next to this file. It is shaped like `CreateKnownCompanyRequest` (`{ "name", "domains" }`), and each element is one `POST /api/known-companies`.

**Status (2026-11-18):** the owner delegated the check (decide autonomously, 01 §3). The list passed the API rules (12/12 accepted in the live E2E run, 05 §7). Add it with the admin account after the first real sign-in (Admin → Known companies, or `POST /api/known-companies` per entry).

## API rules the list was checked against

- **Name:** 2–100 characters of letters, digits, spaces and `& . , ' ( ) -`. It is trimmed with spaces collapsed, and it must be unique ignoring case and accents (409 `COMPANY_EXISTS`).
- **Domains per company:** 1–10 on create (400 `VALIDATION_FAILED`). Adding a domain past 10 gives 422 `TOO_MANY_DOMAINS`.
- **Domain format:** each domain must pass `DomainValidator` (no IP, port, path, scheme, `@` or local name, and not a public suffix), otherwise 400 `INVALID_DOMAIN`. It is stored as its registrable domain in lower-case ASCII (`careers.x.com` becomes `x.com`), so subdomains never need listing.
- **Free mail:** a free-mail domain gives 422 `FREE_MAIL_DOMAIN` (`backend/src/main/resources/common/free-mail-domains.txt`). That file contains **`zoho.com`** (see "Not included").
- **Uniqueness:** a domain belongs to one company only (409 `DOMAIN_TAKEN`). The last domain of a company can't be removed (409 `LAST_DOMAIN`).
- **Endpoint:** ADMIN only, and every change needs the XSRF header.

**Checks run on `known-companies.json`:**

- Every name matches the regex.
- Each company has 1–3 domains.
- There are no duplicate names or domains.
- No domain is on the free-mail list.
- Every domain is already registrable under Guava 33.7.2-jre, the project's `guava.version`. `InternetDomainName.topPrivateDomain()` returns each domain unchanged, including `frog.co` and `amazon.jobs`.

## How the list behaves in the analysis (01 §3 P8)

- **C3, name match:** the company's name, without trailing legal words, is matched as whole words in the letter.
  - "Tata Consultancy Services" matches "Tata Consultancy Services Limited", but **not "TCS" alone**.
  - "HCL Technologies" does **not** match "HCLTech".
  - "LTIMindtree" does **not** match "LTM".
  - The AI's company name is a fallback that can only add risk (R-A).
- **C4, look-alike brands:** brands come from the name with spaces removed, plus the first label of each domain, ignoring brands under 5 characters.
  - So `tcs`, `ibm`, `hcl`, `ltm` and `frog` add no look-alike brand.
  - **IBM gets no C4 protection at all; only C3 applies.**
  - Adding `tcsion.com` gives TCS the brand `tcsion`, and `hcltech.com` gives the brand `hcltech`.
- **What a missing domain costs:** a real but unlisted company domain that contains the brand gets C4 (+30). Together with C3 (+20) that is **High**. This is why subsidiaries were checked below.

## Seed table

Confidence levels:
- **high** = the company's own page names this domain as its recruiting or official email domain.
- **medium** = the company owns the domain (proven), but no official statement says recruiting mail comes from it. These rows are marked **(M)**. Listing an owned domain can't cause a false alarm; it only means mail from it counts as official.

| # | Name (API `name`) | Domains (API `domains`) | Conf. | Evidence (official sources) | Notes: ATS / portals / other senders |
|---|---|---|---|---|---|
| 1 | Tata Consultancy Services | `tcs.com` | high | [tcs.com Recruitment Fraud Alert (India)](https://www.tcs.com/careers/india/recruitment-fraud-alert): off-campus drive mail "comes from the TCS domain alone (@tcs.com)". TCS does not send offers from Gmail, Rediffmail, Yahoo or Hotmail. Reporting address: careers@tcs.com. *The direct fetch returned 403 (bot wall), so this text comes from the search engine's copy of that official page.* | Portals: NextStep (`nextstep.tcs.com`, own) and TCS iON. Helpdesks such as `ilp.support@tcs.com` and `iONservicedesk@tcs.com` are on tcs.com. |
| | | `tcsion.com` **(M)** | medium | [tcsion.com NQT hub](https://www.tcsion.com/hub/national-qualifier-test/) is TCS iON's own NQT registration and hall-ticket portal. [TCS press release on tcs.com](https://www.tcs.com/who-we-are/newsroom/press-release/tcs-ion-open-its-national-qualifier-test-to-all-corporates) confirms TCS iON runs the NQT. | Hall-ticket mail appears to come from tcs.com. Listing tcsion.com mainly avoids false alarms on iON mail and adds the brand `tcsion` for C4. |
| 2 | Infosys | `infosys.com` | high | [infosys.com Recruitment Fraud Alert](https://www.infosys.com/careers/apply/recruitment-fraud-alert.html): the recruitment team "corresponds from email addresses with the domain @infosys.com". [Infosys BPM fraud alert](https://www.infosysbpm.com/careers/apply/recruitment-fraud-alert.html): **the Infosys BPM team also writes from @infosys.com**. *Direct fetch returned 403; the text comes from the search engine's copy.* | Since Oct 2024 Infosys **no longer emails offer letters**; they are only on the career site ([offer validation](https://career.infosys.com/offerValidation)). Portals: `career.infosys.com` and `digitalcareers.infosys.com` (own). |
| 3 | Wipro | `wipro.com` | high | [careers.wipro.com Fraud Awareness](https://careers.wipro.com/content/Fraud-Awareness/) (fetched): it warns about mail "not from email addresses with the domain '@wipro.com'". Reporting address: helpdesk.recruitment@wipro.com. | ATS: careers site on SAP SuccessFactors (the page names "SAP as service provider"). The fraud page is mirrored on `wipro.jibeapply.com` (**Jibe, third-party; not listed**). |
| 4 | Accenture | `accenture.com` | high | [Accenture "Our Process" FAQ](https://www.accenture.com/us-en/careers/explore-careers/area-of-interest/journey-to-accenture) (fetched): "Official communications will only come from an @accenture.com email address". [India important notice](https://www.accenture.com/in-en/careers/support/important-notice): offer documents come only from `india.jobs.accenture.com`, and fraud reports go to india.fc.check@accenture.com. [Scam alert](https://www.accenture.com/in-en/support/email-employment-investment-scam-alert). | **ATS: Workday.** The FAQ says a recruiter may reach out "via email or phone through Workday". Workday notices usually come from `@myworkday.com` (**third-party; not listed; not confirmed for Accenture**). |
| 5 | Cognizant | `cognizant.com` | high | [careers.cognizant.com India notices](https://careers.cognizant.com/india-en/notices/) (fetched). Its fraud-report mailboxes are on cognizant.com: genchrcompliance@cognizant.com (campus/fresher) and TAGcompliance2@cognizant.com, decoded from the page's Cloudflare-obfuscated links. The campus portal `campus2cognizant.cognizant.com` is also on cognizant.com. *No explicit "we only email from @cognizant.com" sentence was found.* | **Campus (GenC) listings are also on Superset** (`app.joinsuperset.com/company/cognizant`; **third-party; not listed**). |
| 6 | Capgemini | `capgemini.com`, `sogeti.com`, `frog.co` | high (all 3) | [Capgemini India fraud alert](https://www.capgemini.com/in-en/fraud-alert/) (fetched): "Our recruitment teams communicate using email addresses with the domain @capgemini.com, @sogeti.com, or @frog.co." The [UK page](https://www.capgemini.com/gb-en/fraud-alert/) says the same. | Sogeti and frog are Capgemini brands. A letter naming Capgemini sent from them now passes C3. |
| 7 | HCL Technologies | `hcltech.com`, `hcl.com` | high | [HCLTech "Identify Genuine Job Offers"](https://www.hcltech.com/careers/genuine-job-offers): the recruitment team "corresponds from email addresses with the domain @hcl.com or @hcltech.com". *The direct fetch timed out, so this text comes from the search engine's copy (seen twice).* [freshers.hcltech.com](https://freshers.hcltech.com/) (fetched): "We only send emails from @hcltech.com". Reporting address: foi@hcltech.com. | The offer is genuine only if the Candidate Portal link works. TechBee offers come from @hcltech.com ([hcltechbee.com](https://www.hcltechbee.com/)), so `hcltechbee.com` is not needed. Careers site: `careers.hcltech.com`. |
| 8 | Tech Mahindra | `techmahindra.com` | high | [careers.techmahindra.com Recruitment_Fraud.pdf](https://careers.techmahindra.com/CPDOC/Recruitment_Fraud.pdf) (fetched, text extracted): "If the email / Offer Letter originates from a public or free domain and not from the authorized techmahindra.com domain, it is fake". *The PDF is marked "© Tech Mahindra 2017".* | Offer letters carry a QR code that leads to `careers.techmahindra.com`. Verification: RMGOfferVerification@TechMahindra.com. Reporting: corporateombudsman@techmahindra.com. |
| 9 | LTIMindtree | `ltm.com` | high | [ltm.com Recruitment Fraud Alert](https://www.ltm.com/careers/recruitment-fraud-alert) (fetched): "LTM only communicates from the official domain 'ltm.com'". The page header says "LTIMindtree is now LTM". [Business Standard, 11 Feb 2026](https://www.business-standard.com/companies/news/ltimindtree-rebrands-to-ltm-limited-126021101202_1.html) reports the rebrand and the legal rename to LTM Limited. | **ATS: RippleHire, per one anonymous forum post only (unverified; third-party; not listed).** |
| | | `ltimindtree.com` **(M)** | medium | The company's primary domain before the rebrand. `https://www.ltimindtree.com/contact-us/` returns **301 to `https://www.ltm.com/contact-us/`**, which shows the same company still controls it. | Kept so that genuine pre-2026 letters from @ltimindtree.com are not flagged. Remove it if you want ltm.com only. |
| 10 | IBM | `ibm.com` | high | [ibm.com/careers/phishing-scams](https://www.ibm.com/careers/phishing-scams) (fetched): "All legitimate correspondence from IBM will originate from an ibm.com email address." | IBM says it sometimes works with **authorised recruitment agencies**, which send from non-IBM domains. IBM India addresses such as `in.ibm.com` reduce to ibm.com. **No C4 brand (`ibm` is under 5 characters).** |
| 11 | Deloitte | `deloitte.com` | high | [Deloitte India advisory for career aspirants](https://www.deloitte.com/in/en/careers/explore-your-fit/students/advisory-for-career-aspirants.html) (fetched): verify offers or report fraud at intalentaquery@deloitte.com, and only an authorised Deloitte official can confirm an offer. It warns about deceptive domains. *No explicit "only from @deloitte.com" sentence was found.* | Portals: `jobsindia.deloitte.com` and `southasiacareers.deloitte.com` (own). **Other member firms use country domains (e.g. deloitte.co.uk). These are not listed, because there is no evidence of Indian campus mail from them.** A genuine letter from one would be flagged C4. |
| 12 | Amazon | `amazon.com` | high | [amazon.jobs Fraud Alert (India)](https://amazon.jobs/content/en/how-we-hire/fraud-alert-india) (fetched): "If the email address doesn't end with @amazon.com, it's most likely not legitimate." Offer documents can only be downloaded from `joining.docs.amazon.com`. | — |
| | | `amazon.jobs` | high (owned) | It hosts Amazon's own fraud-alert page above and Amazon's official job site (`passport.amazon.jobs`). | No statement was found that recruiting mail is sent from amazon.jobs; it is listed as the official careers site. `amazon.in` is **not** listed (no evidence of HR mail). |

## Not included, and why

- **Google, Microsoft:**
  - Neither has an official page that names its recruiting email domain; searches of google.com, support.google.com, careers.microsoft.com and microsoft.com found none.
  - There is also a real false-alarm risk. C3 counts any whole-word mention, and many genuine letters from unlisted companies say "Google Meet", "Google Form" or "Microsoft Teams". Each of those would get +20.
  - If you still want them, use `{"name":"Google","domains":["google.com"]}` and `{"name":"Microsoft","domains":["microsoft.com"]}` (medium).
- **Zoho:**
  - `zoho.com` is on the app's free-mail list, so the API refuses it (422).
  - No official Zoho recruitment-fraud page was found. `zohocorp.com` appears only in abuse and sales contacts.
  - Add `zohocorp.com` only after you verify it.
- **Persistent Systems:** [its fraud page](https://www.persistent.com/careers/recruitment-fraud-awareness/) gives the reporting addresses campus@persistent.com and RecruitmentEnquiries@persistent.com, but no "only from" statement. It is a reasonable later addition (`persistent.com`, medium).
- **Mphasis:** not researched.
- **Subsidiary domains not needed by the evidence:**
  - `infosysbpm.com`: BPM recruiters use @infosys.com.
  - `hcltechbee.com`: TechBee offers come from @hcltech.com.
  - `edgeverve.com` and `hcl-software.com`: not researched. They would only matter if a letter from them names the parent.
- **Third-party ATS domains:** never listed, by instruction. Seen in the research: `myworkday.com` (Accenture via Workday, unconfirmed), `jibeapply.com` (Wipro), `joinsuperset.com` (Cognizant campus), RippleHire (LTIMindtree, anecdotal).
  - A genuine letter sent from an ATS domain will get C3 (+20, Low) when it names a listed company.
  - The address `accenture@myworkday.com` does not trigger C4, because the host is just `myworkday.com`.
  - A tenant subdomain in the **sender host** does trigger C4. `LookalikeDomainCheck.SenderParts` also compares subdomain labels, so a sender like `x@accenture.<ats>.com` gets +30, and with C3 that is High. This is a known limitation of D-03, not a list problem.

## Caveats

- A matching domain is not proof. Infosys, TCS and HCLTech all warn that sender addresses get spoofed, and the app does not check SPF/DKIM.
- TCS and Infosys block automated fetches (403), and the HCLTech page timed out. Their wording comes from the search engine's copy of the official URL listed. Open those three pages in a browser before you sign off.
- Tech Mahindra's PDF is dated 2017. Its domain rule is still consistent with the current careers site.
