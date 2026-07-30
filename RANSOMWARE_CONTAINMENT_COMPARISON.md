# Ransomware Containment Speed: Automated vs. Manual Response

## Purpose and framing

This document lays out the comparison for the paper's claim that this SOAR system reduces
ransomware containment time relative to a manual SOC workflow.

**The claim this document supports is deliberately narrow and honest:** the system does not
claim to *prevent* ransomware encryption outright — for the fastest strains, some encryption may
already be underway before any detection signature fires, automated or manual. What the system
does demonstrably do is collapse the **detection-to-containment-action** latency from
minutes-to-tens-of-minutes (manual) to milliseconds (automated), which matters because it
determines how much of the attack's window — continued encryption, lateral spread to other
hosts/shares, continued C2 communication — is cut short. This is the same framing the security
industry uses for its own equivalent metric, "breakout time" (see below) — the paper should adopt
that framing rather than an absolute-prevention claim, which the evidence does not support and
would not survive scrutiny.

---

## The three numbers that anchor the comparison

### 1. How fast ransomware actually encrypts (the attacker's clock)

**Primary, peer-reviewed source:**
> Davies, S., & Macfarlane, R. (2026). Comprehensive performance benchmarking and comparative
> analysis of active ransomware threats. *Computers & Electrical Engineering*.
> https://doi.org/10.2139/ssrn.5367030

Benchmarked 29 active crypto-ransomware strains under controlled conditions. Encryption
throughput ranged from **33 MB/s to 2.79 GB/s**; the fastest strain (Trigona) encrypted **84×
faster** than the slowest (CL0P). Also found frequent use of intermittent encryption specifically
to maximize throughput while evading detection.

**Secondary, illustrative source (not peer-reviewed, cite as industry research):**
> Splunk SURGe. (2022). Gone in 52 Seconds…and 42 Minutes: A Comparative Analysis of Ransomware
> Encryption Speed. https://www.splunk.com/en_us/blog/security/gone-in-52-seconds-and-42-minutes-a-comparative-analysis-of-ransomware-encryption-speed.html

Lab-measured 10 ransomware families encrypting ~100,000 files (53.93 GB). Median variant: **42
min 52 sec**. Fastest (LockBit): **5 min 50 sec**. Slowest: ~3.5 hours. Useful because the
"minutes to encrypt 100k files" framing is more concrete for a reader than raw MB/s — use it as a
supporting illustration alongside the peer-reviewed throughput numbers, not as the sole source.

### 2. How fast attackers move laterally once inside (the industry's own equivalent metric)

> CrowdStrike. (2026). 2026 Global Threat Report.
> https://www.crowdstrike.com/en-us/global-threat-report/

Tracks **"breakout time"**: initial access → lateral movement. **Average 29 minutes in 2025**
(down from 48 minutes in 2024), with the **fastest observed breakout at 27 seconds**. Vendor
telemetry report, not peer-reviewed — cite it as an industry report explicitly, the same tier as
Verizon's DBIR or Mandiant's M-Trends, which is standard practice in security papers for this kind
of real-world-scale statistic that academic venues don't independently produce.

### 3. How slow — and how variable — manual human response is

> Sundaramurthy, S. C., Bardas, A. G., Case, J., Ou, X., Wesch, M., McHugh, J., & Rajagopalan, S.
> R. (2015). A Human Capital Model for Mitigating Security Analyst Burnout. *Proceedings of the
> Eleventh USENIX Conference on Usable Privacy and Security (SOUPS 2015)*.
> https://www.usenix.org/conference/soups2015/proceedings/presentation/sundaramurthy

A 6-month anthropological field study of a real corporate SOC (peer-reviewed, USENIX). Finds that
analyst burnout — driven by alert volume, fatigue, and organizational factors — systematically
degrades judgment quality and response speed/consistency when triaging security events.

**Deliberately not used as a source of a specific "X minutes" manual-response number.** Multiple
generic SOC-metrics blog posts claim a ~10-minute Mean-Time-To-Acknowledge SLA and a 20-40 minute
investigation duration, but none of them cite an underlying study, sample size, or methodology —
that is marketing-adjacent content, not something to build a thesis's central comparison number
on. Recommendation: use Sundaramurthy et al. to make the **qualitative** claim (manual response is
slow, inconsistent, and degrades under load) rather than asserting a specific duration you can't
actually back up. See "Areas to fill in" below for how to get a real number if you want one.

---

## The comparison table (draft — has placeholders you need to fill in)

| Actor / path | Time | Source | Status |
|---|---|---|---|
| Fastest ransomware, full encryption of ~100k files (LockBit) | 5 min 50 sec | Splunk SURGe, 2022 | ✅ cited |
| Median ransomware, full encryption of ~100k files | 42 min 52 sec | Splunk SURGe, 2022 | ✅ cited |
| Ransomware encryption throughput range (29 strains) | 33 MB/s – 2.79 GB/s | Davies & Macfarlane, 2026 | ✅ cited, peer-reviewed |
| Fastest recorded attacker breakout (lateral movement) | 27 sec | CrowdStrike, 2026 GTR | ✅ cited |
| Average attacker breakout time | 29 min (2025) | CrowdStrike, 2026 GTR | ✅ cited |
| Manual SOC response quality/speed under realistic conditions | *(qualitative: degraded by burnout, alert fatigue — no specific number claimed)* | Sundaramurthy et al., 2015 | ✅ cited, qualitative only |
| **This system's measured detection→containment-dispatch time** | **`[FILL IN]`** | **this thesis — measured, not cited** | ⬜ **not yet filled in** |

---

## Areas you need to fill in yourselves

### 1. Your own system's measured time-to-containment (required)

This is the number the entire comparison is built to receive, and it's the one piece nothing
online can give you — it has to come from your own `StageTimer` instrumentation.

- Run the existing pipeline (`WorkflowQueueListener` → `WorkflowExecutor` → `CommandRoutingListener`)
  against a simulated ransomware alert (e.g. `scripts/simulate_all_sources.py`, or one of the
  `WorkflowEngine/sample_alerts/*.json` files via a live RabbitMQ run per `TESTING_GUIDE.md` Tier 3).
- Let `StageTimer` write its CSV (`benchmark_output/benchmark_run_<date>.csv`), then run
  `scripts/summarize_benchmark.py` to get the aggregated end-to-end time.
- Run it enough times (recommend **n ≥ 10** trials) to report a mean and a range/standard
  deviation, not a single cherry-picked number — a single run is not a defensible thesis result.
- The number you want specifically is the sum through `command_dispatch` /
  `registry_route_dispatch` — i.e., detection to the point the mitigation command is actually
  issued to `OpenDaylightModule`. Decide explicitly whether you're also including actual SDN
  switch reconfiguration time (if measurable) or stopping at command dispatch, and state that
  scope decision plainly in the paper.
- Fill the result into the table above once you have it, with n and range/stdev alongside the mean.

### 2. Decide how far to push the "manual baseline" claim

You already decided (per this session's earlier discussion) to use cited industry statistics
rather than run your own timed user study. Given that, decide explicitly between:

- **Option A (recommended, lower risk):** Keep the manual side entirely qualitative — cite
  Sundaramurthy for *why* manual response is slow/inconsistent, and let the quantitative
  comparison rest on ransomware-speed vs. your-measured-speed only, without asserting a specific
  manual-response duration.
- **Option B (higher risk, more rhetorically forceful):** State the ~10 min MTTA + 20-40 min
  investigation figures as an illustrative industry rule-of-thumb, but you must explicitly caveat
  in-text that these numbers are not independently verified/peer-reviewed and are included only as
  a commonly-cited approximation — do not present them with false precision.
- **Option C (if time allows):** Run a small, even informal, timed walkthrough with 1-3 available
  people (labmates, advisor) as an illustrative anchor point, explicitly labeled as a small,
  non-statistically-powered demonstration rather than a controlled study. Check with your advisor
  whether this needs ethics/IRB review at your institution before doing this, even informally.

### 3. State the environment-mismatch limitation explicitly

None of the three cited sources measure the exact same environment as your own system (different
labs, different ransomware samples, different network topologies). Add an explicit limitations
paragraph acknowledging this is a **comparison of published, independently-measured figures
against your own system's measured figures**, not a single controlled A/B experiment — this is
the same honest framing already used throughout `SDK_USABILITY_AUDIT.md` and should stay
consistent with it.

### 4. Optional: strengthen the ransomware-detection-speed side further

If you want more peer-reviewed weight specifically on *detection* speed (as opposed to encryption
speed), these appeared during citation research and are worth a closer look if relevant to your
related-work section (not yet verified in depth — check before citing):
- CryptoLock-style detection-after-N-files-encrypted studies (~10 files)
- "Peeler" (arXiv, kernel-event profiling for ransomware detection) — reported ~16.4 sec average
  detection for screen-locker ransomware

---

## Sources considered and rejected for this comparison

For transparency — these were found during research but are **not used** in the comparison above,
and why:

- **IBM Cost of a Data Breach Report 2025** — reports mean time to identify + contain a breach at
  241 days (158 days identify + 83 contain). Rejected because this measures the lifecycle of
  breaches that went **undetected** for a long time (e.g. slow-moving APTs, insider threats), not
  the "alert has already fired, how fast is containment" scenario this comparison is about — using
  it would conflate two different kinds of latency.
- **Sophos Active Adversary Report** — reports median 5-day time-to-detect. Same issue: measures
  detection latency for attacks that hadn't yet been noticed, not post-alert containment speed.
- **Generic SOC-metrics blog posts (MTTA ≈ 10 min, investigation 20-40 min)** — see the discussion
  under source #3 above; no visible underlying study or methodology, not used as a cited number.
