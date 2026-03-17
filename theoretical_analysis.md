Analysis of Ransomware 

This section details the operational mechanisms and strategic behaviors of contemporary ransomware families (from 2020 onwards): Sodinokibi (REvil), Clop, Phoenix CryptoLocker, RansomHub, and Akira. It leverages the MITRE ATT&CK framework as a structured taxonomy to classify and present the observed Tactics, Techniques, and Procedures (TTPs). 

 

Sodinokibi 

Sodinokibi, also widely known as REvil (Ransomware Evil), is a highly sophisticated and notorious Ransomware-as-a-Service (RaaS) operation that first appeared in April 2019 [64]. REvil quickly rose to prominence by targeting large enterprises, managed service providers (MSPs), and critical infrastructure with a "big-game hunting" strategy, demanding multi-million dollar ransoms. The group widely the double extortion model, not only encrypting a victim's files but also exfiltrating sensitive data and threatening to publish it on their "Happy Blog" leak site to maximize pressure [65]. The RaaS model allowed REvil's core developers to lease the ransomware to a wide range of affiliates, who carried out the attacks in exchange for a percentage of the profits. This structure enabled a rapid scaling of their operations and a variety of attack vectors, leading to high-profile incidents such as the attacks on JBS Foods, Kaseya, and Travelex. Though international law enforcement actions led to the group's takedown in 2021 and arrests in early 2022, its legacy and codebase continue to influence the ransomware landscape [65]. 

 

Adversary Tactics, Techniques, and Procedures (TTPs) using MITRE ATT&CK 

As a RaaS platform, Sodinokibi's TTPs were not uniform but reflected the diverse skills of its affiliates [66]. The attacks were typically human-operated, involving careful reconnaissance and lateral movement before the final ransomware deployment. This allowed attackers to tailor their approach to maximize impact [66]. The core of their strategy was double extortion, where data was stolen before being encrypted, creating two powerful levers for coercing victims into payment. The observed TTPs represent a combination of common and advanced techniques, focusing on exploiting vulnerabilities, acquiring credentials, and disabling security measures [64]. 

 

TTPs 

Sodinokibi affiliates employed a range of TTPs across the attack lifecycle. Their methods included exploiting public-facing vulnerabilities, phishing, and using stolen credentials for initial access. Once inside, they used tools like Cobalt Strike for command and control and PsExec for lateral movement [65]. Credential dumping tools like Mimikatz were standard for privilege escalation. To evade defenses, they terminated security processes and deleted volume shadow copies. The final impact was achieved through robust encryption of files and the threat of public data exposure. 

 

Initial Access (TA0001) 

Sodinokibi affiliates used a variety of methods to gain their initial foothold in a target network. One of the most common vectors was the exploitation of vulnerabilities in public-facing applications, such as Oracle WebLogic (CVE-2019-2725), Citrix ADC (CVE-2019-19781), and Pulse Secure VPNs (CVE-2019-11510) [64]. Another significant entry point was through brute-force attacks on exposed Remote Desktop Protocol (RDP) services [66]. Phishing campaigns, using emails with malicious attachments or links, were also a primary method to deliver the initial payload. In some of the most impactful attacks, Sodinokibi leveraged supply-chain compromises, most notably the Kaseya VSA zero-day vulnerability (CVE-2021-30116), to distribute the ransomware to the clients of managed service providers [65]. 

 

Execution (TA0002) 

Once initial access was achieved, affiliates used various techniques to execute malicious code. PowerShell was frequently used to load the ransomware into memory reflectively, avoiding writing the binary to disk. In many cases, especially in the Kaseya attack, legitimate administrative tools were used to launch malicious scripts. The ransomware itself was highly configurable through a JSON file, allowing affiliates to tailor the execution with different command-line arguments, such as enabling faster encryption or specifying targets [65]. 

 

Batch Files 

To automate parts of their attacks and evade detection, Sodinokibi operators were observed using batch scripts. These scripts were often used to delete log files and other forensic evidence, clearing their tracks after compromising a system [67]. 

 

Persistence (TA0003) 

To ensure continued access to the compromised network, Sodinokibi affiliates established persistence mechanisms. A common technique was to create scheduled tasks that would re-launch the malware upon system reboot. They also modified registry keys, particularly in the "Run" key, to maintain execution across user sessions. In some instances, affiliates installed legitimate remote access software like TeamViewer or AnyDesk to create a persistent backdoor into the network [65]. 

 

Privilege Escalation (TA0004) 

After gaining an initial foothold, attackers needed to escalate their privileges to gain administrative control. Sodinokibi had a built-in capability to exploit a known Windows privilege escalation vulnerability (CVE-2018-8453) to gain SYSTEM-level access. This was a relatively rare feature for ransomware at the time. Affiliates used credential dumping tools like Mimikatz to scrape passwords and NTLM hashes from the memory of the Local Security Authority Subsystem Service (LSASS) [66]. 

 

Defense Evasion (TA0005) 

Sodinokibi employed numerous defense evasion techniques. The malware was designed to terminate a long list of processes and services related to security software, databases, and backup utilities before encryption. In the Kaseya attack, a cloud-based managed service provider platform, PowerShell was used to disable various features of Windows Defender. A notable technique was the ability to reboot the infected machine into Safe Mode with networking before starting the encryption process, which would bypass many endpoint protection tools [66]. The ransomware also deleted volume shadow copies to prevent easy system recovery. 

 

Credential Access (TA0006) 

Acquiring valid credentials was central to Sodinokibi's ability to move laterally and escalate privileges. The most prevalent technique was OS Credential Dumping, using tools like Mimikatz to extract credentials from LSASS [66]. Affiliates also engaged in Pass-the-Hash (PtH) and Pass-the-Ticket (PtT) attacks within Active Directory environments to impersonate users and access other systems. 

 

Discovery (TA0007) 

Once inside the network, operators conducted extensive reconnaissance to map the environment and identify high-value data. They used network scanning tools like Nmap to identify live hosts and open ports. Legitimate administrative tools and native Windows commands were used to gather information about the network configuration, domain structure, and user accounts [65]. 

 

Lateral Movement (TA0008) 

To spread across the network, Sodinokibi affiliates relied heavily on stolen credentials. Remote Desktop Protocol (RDP) was frequently used for hands-on intrusion and movement between systems. Tools like PsExec were also commonly used to execute the ransomware payload on remote machines. The attackers often leveraged compromised administrative accounts to access network shares and deploy the malware [68]. 

 

Collection (TA0009 - For Double Extortion) 

As a key component of their double extortion strategy, Sodinokibi affiliates exfiltrated large amounts of sensitive data before encrypting the network. They specifically targeted valuable information such as contracts, financial records, intellectual property, and customer data. This data was often staged on a compromised server before being exfiltrated [69]. 

 

Exfiltration (TA0010 - For Double Extortion) 

After collecting the data, it was exfiltrated to attacker-controlled infrastructure. Affiliates were observed using legitimate cloud storage services like Megaupload to upload the stolen data, which could blend in with normal network traffic. In some cases, command-and-control frameworks like Cobalt Strike were used to facilitate data exfiltration [68]. 

 

Command and Control (TA0011) 

Throughout the attack, operators maintained command and control (C2) to manage their tools and receive information from the compromised network. Sodinokibi could send information about the victim machine to its C2 servers. The C2 communication was often encrypted. In many human-operated attacks, affiliates used post-exploitation frameworks like Cobalt Strike, which provided robust and flexible C2 capabilities [68]. 

 

Impact (TA0040) 

The final objective of a Sodinokibi attack was to encrypt the victim's data and extort a ransom payment. The ransomware used a strong hybrid encryption scheme, typically employing Salsa20 for file encryption and an elliptic-curve algorithm (Curve25519) to protect the keys. This made decryption without the attacker's key practically impossible. Before encryption, it would delete Volume Shadow Copies to inhibit system recovery. After encryption, a ransom note was dropped in each folder, and the desktop wallpaper was often changed to display the ransom demand [65]. 

 

Behavioral Patterns and Evolution 

Sodinokibi marked a significant evolution in the ransomware landscape. Its emergence shortly after the retirement of GandCrab suggested a rebranding by a highly experienced group. The RaaS model allowed it to scale its operations massively, leveraging a wide network of affiliates with varying skill levels. The group was a popularizer of the double extortion tactic, which has since become a standard practice for major ransomware gangs [65]. Sodinokibi was known for its "big-game hunting" approach, specifically targeting large, high-value organizations to demand enormous ransoms. The devastating Kaseya supply-chain attack highlighted their capability to execute highly complex and widespread campaigns. Despite its eventual takedown by international law enforcement, the code and tactics of Sodinokibi continue to influence new and emerging ransomware threats [70]. 

 

Implications for Proactive Defense 

Defending against a threat like Sodinokibi requires a multi-layered, defense-in-depth strategy. A primary focus should be on hardening the attack surface by regularly patching all systems, especially public-facing applications and VPNs, and securing RDP access with multi-factor authentication (MFA) [64]. Since attackers heavily rely on stolen credentials, robust identity and access management controls are critical. Network segmentation can help contain a breach and prevent lateral movement [70]. Advanced security solutions like Endpoint Detection and Response (EDR) are necessary to detect the behavioral indicators of an attack [66]. Crucially, organizations must maintain a comprehensive and regularly tested backup and recovery plan that includes offline and immutable backups, as this is the most reliable way to recover from a destructive ransomware attack without paying the ransom. 

 

Clop Ransomware 

Clop, sometimes stylized as "Cl0p," is a dangerous ransomware variant from the CryptoMix family that first appeared in 2019 [71]. It is wielded by the prolific Russian-speaking cybercriminal group known as TA505, which is also tracked under various names including FIN11 and Lace Tempest [72]. This group has been active since at least 2014 and is a major force in global malware distribution. Clop has become infamous for its high-profile attacks against major corporations, its pioneering use of double and even quadruple extortion tactics, and its recent strategic shift toward exploiting zero-day vulnerabilities in secure file transfer systems [71]. 

 

Adversary Tactics, Techniques, and Procedures (TTPs) using MITRE ATT&CK 

Clop and its affiliates utilize a broad and dynamic set of TTPs to execute their attacks. Their methodology is marked by a dual approach, combining opportunistic, large-scale campaigns with highly targeted "big game hunting" aimed at large enterprises. The group's tactics have a pronounced focus on large-scale data exfiltration for extortion, in some cases forgoing the encryption phase of the attack entirely [73]. 

 

Initial Access (TA0001) 

Clop has demonstrated significant adaptability in gaining initial access, historically using multiple vectors before specializing in its most impactful technique: Exploit Public-Facing Application. This has become the group's signature, showcasing a formidable ability to discover and weaponize zero-day vulnerabilities in managed file transfer (MFT) solutions [71]. This strategy was evident in the mass exploitation of vulnerabilities in the Accellion File Transfer Appliance (FTA) in 2020 and 2021, and later in the Fortra GoAnywhere MFT vulnerability (CVE-2023-0669) in early 2023 [74]. Their most widespread campaign involved the exploit of a SQL injection zero-day (CVE-2023-34362) in Progress MOVEit Transfer starting in May 2023, which led to the breach of hundreds of organizations worldwide [75]. Before this pivot to zero-day exploits, Phishing was a primary initial access method for TA505. These campaigns often used malicious HTML attachments that led to macro-enabled documents, which in turn downloaded loaders like Get2 to deploy malware such as SDBot and FlawedAmmyy [71]. Additionally, the group has been known to compromise External Remote Services, such as Remote Desktop Protocol (RDP), to gain its initial foothold. 

 

Execution (TA0002) 

Once an initial foothold is established, Clop operators use several techniques to execute their payloads and other malicious tools. They heavily rely on Command and Scripting Interpreters, frequently using PowerShell for downloading additional malware and executing commands, as well as the standard Windows Command Shell for various script executions [76]. To ensure their ransomware runs persistently, they have been known to create scheduled tasks through Scheduled Task/Job: Scheduled Task, which triggers execution even after system reboots. The ransomware also makes use of Shared Modules by calling legitimate native APIs for execution, a technique that helps it blend in with normal system operations and evade simple detection methods [76]. 

 

Persistence (TA0003) 

To maintain long-term access to compromised networks, Clop employs several persistence mechanisms. A key TTP in their zero-day exploit campaigns is the deployment of a Server Software Component: Web Shell. For instance, the DEWMODE web shell was used in the Accellion attacks, and a web shell named LEMURLOOT was installed on vulnerable MOVEit Transfer servers [75]. These web shells grant the attackers persistent remote control over the server, facilitating command execution and data theft. The SDBot RAT, often used in the initial stages of an attack, has been observed using Event Triggered Execution: Application Shimming to maintain persistence [76]. In other cases, Clop has been reported to Create or Modify System Process: Windows Service to ensure its malicious processes launch automatically at startup. A more common technique is the manipulation of Registry Run Keys / Startup Folder, where Clop adds entries to ensure its components execute whenever a user logs on. 

 

Defense Evasion (TA0005) 

Clop is designed with numerous defense evasion capabilities to avoid detection. A primary tactic is to Impair Defenses: Disable or Modify Tools by actively terminating security products. The ransomware is known to specifically target and stop processes and services associated with antivirus software, including attempts to disable Windows Defender and remove Microsoft Security Essentials. To hinder analysis, Clop's executable files are often packed or compressed using Obfuscated Files or Information and may use simple XOR operations to decrypt strings at runtime [77]. A significant evasion tactic is the use of Code Signing, where the malware is signed with valid digital certificates, making it appear as a legitimate application to bypass security checks [73]. The group also performs Indicator Removal on Host: File Deletion by deleting log files and shadow copies to erase their tracks and complicate recovery. Further evasion is achieved through Process Injection, which hides malicious code within legitimate processes, and Masquerading, such as naming their LEMURLOOT web shell "human2.aspx" to mimic a legitimate MOVEit file [76]. 

 

Credential Access (TA0006) 

Gaining access to valid credentials is a critical step for Clop operators, enabling lateral movement and privilege escalation throughout a network. Their primary technique for this is OS Credential Dumping. Attackers frequently deploy well-known tools like Mimikatz to extract plaintext passwords, hashes, and Kerberos tickets directly from the memory of compromised systems [74]. These harvested credentials can then be used to authenticate to other machines on the network, providing a powerful means to expand their foothold without needing to exploit further vulnerabilities. 

 

Discovery (TA0007) 

Upon gaining entry, Clop operators initiate a discovery phase to map the compromised environment and identify valuable targets. They perform System Information Discovery to gather details about the operating system and network configuration. To identify and disable security measures, they conduct Process Discovery, specifically enumerating processes to find and terminate security-related software. The group also performs Account Discovery to identify privileged domain accounts that can be leveraged for further compromise. A crucial part of their reconnaissance is Network Share Discovery, where they actively scan for and enumerate accessible network shares to locate repositories of data for their exfiltration and encryption objectives  [77]. 

 

Lateral Movement (TA0008) 

To spread from their initial point of compromise to other systems across the network, Clop operators use several lateral movement techniques. A common method is using Remote Services: Remote Desktop Protocol, where they leverage previously stolen credentials to log into other machines, blending in with normal administrative traffic. They engage in Lateral Tool Transfer by deploying post-exploitation toolkits like Cobalt Strike to expand their control and move deeper into the network [78]. Additionally, the ransomware has been observed using Exploitation of Remote Services by taking advantage of vulnerabilities in protocols like SMB to propagate between systems on the internal network. 

 

Collection (TA0009 - For Double Extortion) 

Data collection is a cornerstone of Clop's double extortion strategy, where they steal sensitive information before deploying the encryption payload [75]. This stolen data is used as powerful leverage in ransom negotiations. The operators systematically collect Data from the Local System, targeting financial records, intellectual property, and other confidential documents on compromised workstations and servers. They also perform extensive collection of Data from Network Shared Drive, as these shares often contain a wealth of valuable business information.  

 

Exfiltration (TA0010 - For Double Extortion) 

After collecting sensitive information, Clop operators proceed to the exfiltration phase, moving the stolen data out of the victim's network. This data is often funneled out using Exfiltration Over C2 Channel, where the command-and-control infrastructure is used to transmit the stolen files. In campaigns involving compromised file transfer appliances, they have demonstrated the ability to Exfiltrate to Cloud Storage by using the built-in functionality of the compromised systems themselves. For example, the DEWMODE web shell was specifically used to extract stolen information from Accellion FTA victims and send it to attacker-controlled storage [78]. 

 

Command and Control (TA0011) 

Clop maintains communication with its compromised systems through various command and control (C2) channels. They often use standard Application Layer Protocol, such as HTTP, to blend their C2 traffic with legitimate network activity, thereby evading basic firewall rules. A key part of their operation involves Ingress Tool Transfer, where they download additional malicious tools onto the compromised network. Payloads such as the SDBot RAT, FlawedAmmyy, and Cobalt Strike are frequently transferred to infected systems to aid in reconnaissance, lateral movement, and data exfiltration [78]. 

 

Impact (TA0040) 

The final objective of a Clop attack is to create maximum disruption to force a ransom payment. The primary impact technique is Data Encrypted for Impact. Clop uses strong encryption algorithms like RSA and RC4 to render files inaccessible, appending extensions like ".Clop" to the encrypted files. To prevent victims from recovering their systems, they actively Inhibit System Recovery by deleting shadow volume copies using tools like vssadmin and using bcdedit to disable system recovery options [77]. Central to their strategy is Extortion, where they employ a "double extortion" model. Not only is data encrypted, but the exfiltrated sensitive information is threatened to be published on their "Cl0p^_-LEAKS" dark web site if the ransom is not paid. This has evolved into "quadruple extortion," where they may also contact a victim's customers, partners, and regulators to apply additional pressure. 

 

Behavioral Patterns and Evolution 

Clop's operational history reveals a clear pattern of evolution toward greater sophistication and a focus on high-impact attacks. The group has notably shifted from its initial reliance on broad phishing campaigns to becoming a specialist in exploiting zero-day vulnerabilities in secure file transfer products [73]. This allows them to achieve mass compromise with a single exploit. In recent campaigns, there has been a distinct focus on large-scale data exfiltration for extortion, with file encryption sometimes becoming a secondary or even omitted step in their "encryption-less" attack model [79]. Clop operates as a Ransomware-as-a-Service (RaaS), which allows them to scale their operations by leveraging a network of affiliates for attack execution [72]. Their targeting strategy is often described as "big game hunting," focusing on large, well-funded organizations capable of paying multi-million dollar ransoms. Consistent with many Russian-speaking cybercrime groups, the ransomware contains code that checks for and avoids encrypting systems in Russia and other Commonwealth of Independent States (CIS) countries [73]. 

 

Implications for Proactive Defense 

Defending against a sophisticated and adaptive threat like Clop requires a multi-layered, proactive security strategy. Given Clop's demonstrated success in exploiting software flaws, rigorous and timely vulnerability and patch management is paramount, especially for internet-facing applications like MFT solutions [71]. Although their tactics have evolved, email security remains crucial for defending against phishing, a consistent entry vector for many malware families. This should include both technical filtering and continuous user awareness training. Implementing network segmentation is vital to contain the spread of an attack, limiting the lateral movement of ransomware and minimizing the overall impact of a breach [71]. Advanced Endpoint Detection and Response (EDR) solutions are necessary to detect behavioral indicators of an attack, such as unusual process execution, credential dumping attempts, or efforts to disable security tools. A comprehensive data backup and recovery plan is a critical last line of defense; backups must be regularly tested, stored in an immutable format, and isolated from the primary network to prevent them from being targeted. Enforcing strict access control, including the principle of least privilege and multi-factor authentication (MFA), can significantly hinder an attacker's ability to escalate privileges and move laterally. Lastly, organizations must maintain a well-defined and frequently rehearsed incident response plan and subscribe to threat intelligence services to stay informed about the evolving TTPs of groups like Clop. 

 

Phoenix CryptoLocker 

Phoenix CryptoLocker, also referred to as Phoenix Locker, is a human-operated ransomware that gained notoriety following a high-profile attack against the U.S. insurance giant CNA Financial in March 2021 [80]. This ransomware is believed to be a rebranded variant of the Hades ransomware, which itself is an evolution of WastedLocker. Security researchers assess with high confidence that these ransomware families are operated by the financially motivated Russian cybercrime group known as Evil Corp (also tracked as Indrik Spider and GOLD DRAKE) [81]. The development and rebranding of their ransomware tools are likely a strategic effort by Evil Corp to circumvent U.S. Treasury Department sanctions, which prohibit victims from making ransom payments to the group [82]. By operating under new names like Phoenix, the group attempts to obscure its identity from victims and ransomware negotiation firms. 

 

Adversary Tactics, Techniques, and Procedures (TTPs) using MITRE ATT&CK 

As a human-operated ransomware, the attacks involving Phoenix CryptoLocker are not automated "set it and forget it" campaigns but are instead characterized by hands-on-keyboard activity [81]. Threat actors manually navigate the victim's network, using legitimate tools and credentials to conduct reconnaissance, escalate privileges, and spread laterally before detonating the ransomware [80]. This targeted approach allows for a much more devastating and widespread impact within a compromised organization. 

 

Initial Access (TA0001) 

The operators behind Phoenix CryptoLocker have employed several methods to gain initial entry into target networks. One of the most prominent documented vectors is Drive-by Compromise, where an unsuspecting employee visits a legitimate but compromised website and downloads what appears to be a genuine browser update [80]. This fake update is the initial payload that establishes a foothold. Another significant initial access vector associated with the threat group is the exploitation of External Remote Services. This involves targeting insecurely configured Remote Desktop Protocol (RDP) or using compromised credentials to access Virtual Private Networks (VPNs) [81]. Phishing campaigns that deliver malicious attachments or links are also a common tactic used by the broader group to distribute malware that can serve as a precursor to a ransomware attack. 

 

Execution (TA0002) 

Once inside the network, the execution of malicious commands and tools is critical for the attackers to advance their objectives. The operators of Phoenix CryptoLocker heavily utilize the Command and Scripting Interpreter, particularly legitimate built-in tools like PowerShell and the Windows Command Shell [83]. These tools are used for a wide range of activities, including executing commands directly in memory, injecting malware into legitimate processes, and moving laterally across the network. The use of these native utilities allows the attackers' activities to blend in with normal administrative tasks, making detection more difficult. In some cases, the ransomware itself is executed after being copied to a new location on the host under a different, randomly generated name to evade detection based on the initial binary [84]. 

 

Persistence (TA0003) 

To ensure they maintain access to the compromised environment over an extended period, the attackers establish persistence mechanisms. During the CNA Financial attack, it was reported that between March 5 and March 20, 2021, the threat actors used legitimate credentials to establish persistence on various systems within the network [80]. This could involve creating new user accounts or modifying existing ones to ensure continued access. While specific techniques for Phoenix CryptoLocker are not publicly detailed, related Evil Corp operations have been known to use reverse SOCKS proxies to maintain long-term access to victim environments [82]. By establishing a persistent presence, the attackers can take their time conducting thorough reconnaissance and data exfiltration before deploying the final ransomware payload. 

 

Privilege Escalation (TA0004) 

After gaining initial access, which may be through a non-privileged user account, the attackers must escalate their privileges to gain administrative or system-level control. This elevated access is necessary to disable security software, access sensitive data across the network, and execute the ransomware with the permissions needed to encrypt critical system files. In the attack against CNA, after the initial compromise via a fake browser update on a non-privileged employee's account, the attackers used what was described as "additional malicious activity" to obtain the credentials needed to move forward [80]. While not explicitly detailed, this often involves exploiting local vulnerabilities or using credential dumping tools like Mimikatz to extract passwords from a computer's memory [82]. 

 

Defense Evasion (TA0005) 

Phoenix CryptoLocker and its operators employ several defense evasion techniques to avoid detection by security software and analysts. One notable tactic is Masquerading; the malware executable has been observed using the icon for the legitimate 7-Zip file utility to trick users into running it [84]. Furthermore, the ransomware binary is often signed with a valid digital certificate, an example of Code Signing, in an attempt to make it appear as a legitimate and trusted application. The attackers also practice "living off the land," a technique that involves using legitimate administration tools and accounts to carry out their activities [80]. This approach helps them maintain a low profile, as their actions do not immediately stand out as malicious. After the encryption process is complete, the malware is designed to delete all traces of itself, including the original binaries and any folders it created, a form of Indicator Removal on Host, to frustrate forensic analysis [84]. 

 

Credential Access (TA0006) 

Acquiring legitimate credentials is a central part of the attackers' strategy, facilitating lateral movement and privilege escalation. The operators of Phoenix CryptoLocker are known to use credential dumping tools [80]. Tools like Mimikatz are frequently used in the post-exploitation phase to extract plaintext passwords, hashes, and Kerberos tickets from the memory of compromised Windows systems [82]. These stolen credentials are then used to access other systems and resources within the network, often with elevated privileges, which is a key enabler for the widespread deployment of the ransomware. 

 

Discovery (TA0007) 

Once the attackers have established a foothold and gained sufficient privileges, they engage in extensive discovery to map out the victim's IT environment. Between March 5 and March 20, 2021, during the CNA incident, the threat actors conducted detailed reconnaissance within the network. They use legitimate tools to identify valuable data, locate critical servers, and understand the network topology [80]. This discovery phase is crucial for planning the data exfiltration and for maximizing the impact of the final ransomware deployment. Tools like Advanced Port Scanner and NetScan have been used in associated attacks to discover hostnames and network services [82]. 

 

Collection (TA0009 - For Double Extortion) 

Phoenix CryptoLocker is used in double extortion attacks, meaning that before encrypting the victim's files, the attackers first exfiltrate large amounts of sensitive data [85]. This collected data serves as powerful leverage; if the victim refuses to pay the ransom for the decryption key, the attackers threaten to publicly release the stolen information. During the CNA attack, the threat actors stole sensitive information affecting over 75,000 individuals, which included names, Social Security Numbers, and in some cases, medical information [80]. The collection process is methodical, targeting the most valuable and sensitive data identified during the discovery phase. 

 

Exfiltration (TA0010 - For Double Extortion) 

After collecting the targeted data, the attackers must exfiltrate it from the victim's network to their own servers. This is a critical step in the double extortion model. The stolen data is often compressed into archives to reduce its size and then transferred out of the network. In attacks associated with the group, tools like MegaSync have been used to exfiltrate data to cloud storage services [82]. This method can be difficult to detect as the traffic can be blended with legitimate cloud service usage. The successful exfiltration of data gives the attackers the leverage they need to pressure the victim into paying the ransom. 

 

Command and Control (TA0011) 

Throughout the attack lifecycle, the operators maintain communication with their malware and tools within the compromised network through command and control (C2) channels. These channels are used to send instructions, receive stolen data, and manage the overall operation [85]. While specific C2 details for Phoenix CryptoLocker are scarce, the broader Evil Corp group is known to use sophisticated C2 infrastructure, including custom backdoors and legitimate tools like Cobalt Strike for C2 communications [82]. Unusual network traffic to unknown or suspicious IP addresses can be an indicator of C2 activity. 

 

Impact (TA0040) 

The ultimate goal of a Phoenix CryptoLocker attack is to cause maximum disruption and force a ransom payment. The primary impact technique is Data Encrypted for Impact [85]. The ransomware encrypts a wide range of files on infected systems, including those on network drives, rendering them inaccessible [81]. In the CNA attack, over 15,000 devices were encrypted, including those of remote employees connected via VPN. The ransomware appends the .phoenix extension to encrypted files and creates a ransom note named "Phoenix-Help.txt" in each affected directory. The note contains instructions on how to contact the attackers to negotiate the ransom payment [84]. To further inhibit recovery, the operators often delete system backups and recovery partitions. 

 

Behavioral Patterns and Evolution 

The emergence of Phoenix CryptoLocker is part of a clear behavioral pattern of the Evil Corp cybercrime group. Facing U.S. government sanctions that legally prevent victims from paying ransoms to them, the group has adopted a strategy of rebranding its ransomware to continue its lucrative operations.Phoenix Locker is considered a direct evolution or rebrand of the Hades ransomware, which itself was a 64-bit version of their older WastedLocker ransomware [82]. This continuous evolution shows the group's adaptability and determination to evade sanctions and law enforcement. The attacks are characterized by their "big game hunting" style, targeting large, well-insured organizations from which they can demand multi-million dollar ransoms. The attack on CNA Financial, which resulted in a reported $40 million ransom payment, is a prime example of this strategy [86]. The group's shift to human-operated attacks allows for a more tailored and devastating impact compared to automated ransomware campaigns. 

 

Implications for Proactive Defense 

Defending against a sophisticated, human-operated threat like Phoenix CryptoLocker requires a defense-in-depth strategy that goes beyond simple prevention. Organizations must focus on early detection and response. Given the use of fake browser updates as an initial access vector, stringent user training and web filtering are essential. However, since no single defense is foolproof, implementing strong identity and access management controls, including multi-factor authentication (MFA) and the principle of least privilege, is critical to limit an attacker's ability to escalate privileges and move laterally [81]. Network segmentation can help contain a breach and limit the blast radius of a ransomware deployment. Continuous network monitoring and the use of Endpoint Detection and Response (EDR) solutions are vital for detecting the subtle signs of "living off the land" techniques and the use of legitimate tools for malicious purposes [80].  Lastly, a crucial defense against the impact of any ransomware attack is a robust and regularly tested backup and recovery plan. This includes maintaining offline and immutable backups that cannot be deleted or encrypted by the attackers, ensuring that the organization can restore its data without being forced to pay a ransom. 

 

RansomHub 

RansomHub is a sophisticated and rapidly emerging Ransomware-as-a-Service (RaaS) platform that appeared on the threat landscape in February 2024 [87]. Security researchers have established with high confidence that RansomHub is not an entirely new operation, but rather a rebrand or evolution of the now-defunct Knight ransomware. This lineage is significant, as the source code for Knight was offered for sale on underground forums in February 2024 after its developers ceased operations [88]. Written in the Go programming language, RansomHub is designed for cross-platform compatibility, capable of targeting Windows and Linux environments, with some payloads specifically targeting VMware ESXi servers. The group has quickly gained notoriety by attracting experienced affiliates from other dismantled or disrupted RaaS operations, such as ALPHV/BlackCat and LockBit. This influx of skilled operators has allowed RansomHub to execute high-profile attacks, including a major breach of the Christie's auction house, establishing it as a significant and dangerous player in the cyber extortion ecosystem [89]. 

 

Adversary Tactics, Techniques, and Procedures (TTPs) using MITRE ATT&CK 

As a RaaS platform, RansomHub's TTPs are not monolithic but are instead a reflection of the diverse skill sets of its various affiliate groups [87]. The attacks are characteristically human-operated, involving hands-on-keyboard activity where threat actors manually infiltrate and navigate the victim's network before deploying the ransomware. This allows for a methodical and tailored approach, ensuring maximum impact. The core of their strategy is double extortion, where data is first exfiltrated and then encrypted, giving the operators two forms of leverage to pressure victims into paying. The TTPs observed are a blend of common, effective techniques used across the e-crime landscape, focusing on stealth, credential acquisition, and the disablement of security defenses before the final encryption stage [90]. 

 

Initial Access (TA0001) 

The initial entry into a victim's network is typically handled by the RaaS affiliates, who employ a variety of common but effective techniques. One of the primary vectors is the Exploitation of Public-Facing Applications, where attackers target vulnerabilities in unpatched software, particularly in VPN and RDP platforms [87]. In some observed attacks, affiliates have specifically exploited the ZeroLogon vulnerability (CVE-2020-1472) to gain initial access and elevate privileges [89]. Another prevalent method is Phishing, where carefully crafted emails are used to trick employees into revealing their credentials or executing malicious attachments that provide a backdoor into the network. Furthermore, RansomHub affiliates frequently leverage Valid Accounts by purchasing stolen credentials from initial access brokers (IABs) on dark web forums or using techniques like password spraying. 

 

Execution (TA0002) 

Once the attackers have gained access, they must execute their malicious code to further their objectives. The execution of the RansomHub payload and other tools is often accomplished using the Command and Scripting Interpreter. Affiliates make extensive use of legitimate built-in utilities like PowerShell and the Windows Command Shell to run commands, download additional malware, and launch the ransomware binary. These scripting languages are powerful and are often used to execute payloads directly in memory, which can help evade detection by some security products. For remote execution as part of their lateral movement strategy, operators may also use tools like PsExec or WMI to launch the ransomware on other machines across the network simultaneously [91]. 

 

Persistence (TA0003) 

To ensure they can maintain access to the compromised network over an extended period for reconnaissance and data exfiltration, RansomHub affiliates establish persistence mechanisms. While the ransomware payload itself is not designed for long-term persistence, the human operators will create backdoors to guarantee their access is not lost. A common technique is to create Scheduled Tasks that run malicious scripts or tools at regular intervals or upon system startup. Operators may also create new Valid Accounts, often with administrative privileges, to use as a persistent entry point [92]. In more sophisticated cases, they may install legitimate remote management software like Atera and Splashtop or custom backdoors that communicate with their command-and-control servers, providing them with reliable, on-demand access to the victim's environment [89]. 

 

Privilege Escalation (TA0004) 

After gaining an initial foothold, which is often through a low-privileged user account, the attackers must escalate their privileges to gain administrative or system-level control. This is a critical step that enables them to disable security defenses, access sensitive data, and deploy the ransomware across the entire network. To achieve this, affiliates may use Exploitation for Privilege Escalation by running exploits against known vulnerabilities, such as the previously mentioned ZeroLogon flaw [90]. A more common method involves using credential dumping tools to obtain the passwords of privileged accounts. Additionally, techniques to Bypass User Account Control (UAC) on Windows systems are frequently employed to execute tools with elevated permissions without triggering security prompts, thus maintaining a lower profile. 

 

Credential Access (TA0006) 

Acquiring valid credentials is a central focus for RansomHub affiliates, as it is the key to moving laterally and gaining control of the network. The most common technique used is OS Credential Dumping. Attackers deploy specialized tools like Mimikatz and LaZagne to extract plaintext passwords, NTLM hashes, and Kerberos tickets directly from the memory of compromised systems, particularly from the Local Security Authority Subsystem Service (LSASS) process [92]. In some campaigns, affiliates have also been observed exploiting vulnerabilities in backup software, such as CVE-2023-27532 in Veeam Backup & Replication, to dump credentials from the Veeam database [91]. These stolen credentials can then be reused to access other systems, network shares, and domain controllers. 

 

Discovery (TA0007) 

Once inside the network with sufficient privileges, RansomHub operators conduct extensive discovery to map the IT environment and identify high-value targets. This phase is crucial for planning both data exfiltration and the final encryption attack. They use legitimate, built-in command-line tools for system and network reconnaissance [93]. They also use open-source tools like AngryIPScanner and Nmap to scan for IP addresses and open ports, allowing them to map the network infrastructure and identify active devices [92]. For deeper insights into the Active Directory environment, they often use specialized tools like AdFind and BloodHound. This allows them to identify domain controllers, privileged user accounts, and trust relationships, which informs their lateral movement strategy and helps them locate the most sensitive servers to target. 

 

Lateral Movement (TA0008) 

To spread their presence from the initial point of compromise to other systems across the network, RansomHub affiliates rely heavily on the credentials they have stolen. The primary technique for lateral movement is the use of Remote Services. They frequently use compromised accounts to access other machines via Remote Desktop Protocol (RDP), which can be difficult to differentiate from legitimate administrative activity [87]. They also abuse SMB/Windows Admin Shares, using commands like xcopy or copy to transfer the ransomware binary to other systems. Post-exploitation frameworks like Cobalt Strike or legitimate administration tools like PsExec are commonly used to remotely execute commands and spread the ransomware payload efficiently across hundreds or thousands of machines in the network [91]. 

 

Collection (TA0009 - For Double Extortion) 

As part of their double extortion model, the collection of sensitive data is a non-negotiable step before any encryption occurs. RansomHub affiliates meticulously search for and gather valuable information from Data from Local System and Data from Network Shared Drive. They target files containing intellectual property, financial records, customer data, and personally identifiable information (PII) [94]. Once the target data is identified, it is typically staged in a central location within the compromised network. The operators then Archive Collected Data by compressing it into formats like .zip or .rar. This not only makes the data easier to exfiltrate but also helps to obscure it from data loss prevention (DLP) systems. 

 

Exfiltration (TA0010 - For Double Extortion) 

After collecting and staging the sensitive data, the attackers must move it out of the victim's network to their own infrastructure. This exfiltration is the final step needed to secure their leverage for the extortion phase. A common method is Exfiltration Over Web Service, where affiliates use legitimate and popular cloud storage or file transfer services to upload the stolen data. Tools like Rclone or Filezilla are frequently used for this purpose because their traffic is encrypted and can easily blend in with normal network activity, making it very difficult for defenders to detect the large-scale data theft that is underway [88]. 

 

Command and Control (TA0011) 

Throughout the attack lifecycle, the operators maintain command and control (C2) to manage their tools and orchestrate the intrusion. They typically use common protocols like HTTP and HTTPS for Application Layer Protocol communication to help their C2 traffic blend in with legitimate web traffic and bypass basic firewall rules. Many of the affiliates leverage popular post-exploitation frameworks like Cobalt Strike, which provide robust and flexible C2 capabilities. For the final stages of the attack, including ransom negotiation and the publication of stolen data, RansomHub uses a TOR-based data leak site, which provides a high degree of anonymity for the operators [87]. 

 

Impact (TA0040) 

The ultimate objective of a RansomHub attack is to cripple the victim's operations and force a ransom payment. The primary impact is achieved through Data Encrypted for Impact. The RansomHub binary, written in Go, uses a robust hybrid encryption scheme, such as AES or ChaCha20 combined with an elliptic curve algorithm, to render files inaccessible [92]. To ensure a more effective encryption process, the ransomware includes functionality to Boot or Logon Autostart Execution: Boot into Safe Mode [89]. Rebooting the machine into Safe Mode allows the malware to run in a minimal environment where many security products and other applications are not active, thus preventing interference [93]. Before encryption, it terminates a list of predefined services and processes using Service Stop to ensure files are not locked.Critically, it also performs Inhibit System Recovery by deleting Volume Shadow Copies using VSSAdmin, making it impossible for victims to easily restore their data without using external backups [87]. 

 

Behavioral Patterns and Evolution 

RansomHub's emergence and rapid growth highlight several key behavioral patterns in the modern ransomware ecosystem. Its core identity is that of a rebrand of the Knight ransomware, demonstrating a common tactic where groups relaunch under a new name to evade law enforcement and sanctions [89]. The platform's success in attracting seasoned affiliates from major defunct groups like ALPHV/BlackCat and LockBit indicates that it offers a professional, reliable, and profitable service, quickly filling the void left by its predecessors. Its cross-platform nature, being written in Go, shows an intent to maximize its target base beyond Windows environments to include Linux servers and ESXi hypervisors, which are often high-value targets [91]. A unique feature of the group's business model is allowing affiliates to receive payments directly, with the operators taking only a 10-15% commission, a move designed to build trust after the exit scams of other major groups. The high-profile attack on Christie's demonstrates their ambition and capability to hit "big game" targets for massive payouts [92]. 

 

Implications for Proactive Defense 

Defending against a sophisticated RaaS operation like RansomHub requires a multi-layered, defense-in-depth security strategy. The first line of defense is to harden the attack surface by securing all remote access points with multi-factor authentication (MFA) and implementing a rigorous patch management program for all public-facing applications [87].Since attackers rely on stolen credentials, strong identity and access management controls, including the principle of least privilege, are crucial to limit an attacker's ability to move laterally. Network segmentation is vital to contain any potential breach and prevent the ransomware from spreading from the IT network to critical servers or backups [95]. Advanced Endpoint Detection and Response (EDR) and Extended Detection and Response (XDR) solutions are necessary to detect the behavioral indicators of a hands-on attack, such as credential dumping, lateral movement, and the execution of suspicious scripts [87]. Most importantly, organizations must maintain a robust and regularly tested backup and recovery plan that includes offline and immutable backups, as this remains the most reliable way to recover from a destructive ransomware attack without yielding to the attackers' demands. 

 

Akira 

Akira is a potent Ransomware-as-a-Service (RaaS) operation that surfaced in March 2023, rapidly establishing itself as a significant threat in the cybercriminal landscape [96]. The group has become notorious for its double-extortion tactics, not only encrypting victim data but also exfiltrating it to pressure organizations into paying ransoms that can range from $200,000 to over $4 million. By early 2024, Akira had already impacted over 250 organizations, accumulating approximately $42 million in ransom payments [97]. Its operational ties point towards a Russian origin, with potential links to the now-defunct Conti ransomware group. Akira targets a wide array of industries, from manufacturing and education to critical infrastructure, with a primary focus on organizations in North America, Europe, and Australia [98]. The ransomware is designed to be cross-platform, with variants capable of encrypting both Windows and Linux-based systems, including VMware ESXi servers [99]. 

 

Adversary Tactics, Techniques, and Procedures (TTPs) using MITRE ATT&CK 

As a RaaS operation, Akira's TTPs are executed by its affiliates, who employ a set of strategies to infiltrate networks, escalate privileges, and deploy the ransomware. Their attacks are characterized by a focus on gaining initial access through compromised credentials and exploiting public-facing vulnerabilities [100]. Once inside a network, they conduct thorough reconnaissance, move laterally to gain control of critical systems, and exfiltrate sensitive data before triggering the final encryption payload. This methodical approach is designed to maximize pressure on the victim and ensure a successful payout. 

 

Initial Access (TA0001) 

Akira affiliates primarily gain initial access by exploiting weaknesses in external-facing services. A key vector is the compromise of VPN services that lack multi-factor authentication (MFA) [97]. They have been observed exploiting known vulnerabilities in Cisco products, such as CVE-2020-3259 and CVE-2023-20269, to gain a foothold in target networks [100]. Additionally, Akira affiliates utilize spear-phishing campaigns with malicious attachments or links, abuse of the Remote Desktop Protocol (RDP), and the use of valid credentials that have been stolen or purchased from initial access brokers [98]. 

 

Execution (TA0002) 

Once inside the network, Akira operators use various methods to execute their malicious code. They frequently leverage the Windows Management Instrumentation (WMI) and PowerShell to run commands and scripts. For example, PowerShell scripts are used to obtain and decrypt credentials from Veeam servers and to dump Kerberos tickets from the LSA cache. The ransomware payload itself, often named w.exe, is executed through the command line and can accept various arguments to tailor its behavior [100]. 

 

Persistence (TA0003) 

To maintain long-term access to a compromised network, Akira affiliates establish persistence through several techniques. A common method is the creation of new domain accounts, sometimes with administrative privileges, to ensure continued access even if the initial entry point is closed [100]. In some instances, they have been observed creating an administrative account named "itadm". They also make use of legitimate remote access software like AnyDesk and RustDesk to maintain a backdoor into the victim's environment [96]. 

 

Privilege Escalation (TA0004) 

After establishing an initial foothold, the attackers focus on escalating their privileges to gain control over the entire domain. They employ credential dumping tools like Mimikatz and LaZagne to extract sensitive credentials from the memory of the Local Security Authority Subsystem Service (LSASS) [101]. Another technique involves Kerberoasting attacks to obtain service account credentials [100]. In some sophisticated attacks, they have been observed extracting the NTDS.dit file from domain controllers to access all user account data and password hashes, allowing them to compromise highly privileged accounts. 

 

Defense Evasion (TA0005) 

To operate undetected, Akira affiliates use a variety of defense evasion techniques. They are known to disable security software by using tools like PowerTool to exploit the Zemana AntiMalware driver and terminate antivirus-related processes. They also attempt to hide their presence by modifying the registry to conceal newly created user accounts from the login screen [96]. In some cases, they have been observed using a "Bring Your Own Vulnerable Driver" (BYOVD) attack to disable security products. Furthermore, to bypass detection, they may deploy different ransomware variants, such as "Megazord," against different system architectures within the same attack [100]. 

 

Credential Access (TA0006) 

The acquisition of valid credentials is a critical part of Akira's attack chain, enabling lateral movement and privileged access. Their primary method is OS Credential Dumping, specifically targeting the LSASS process memory to extract plaintext passwords and hashes using tools like Mimikatz and LaZagne [100]. They have also been observed using PowerShell scripts to harvest credentials from Veeam backup servers. By obtaining these credentials, they can impersonate legitimate users and move through the network with less chance of detection. 

 

Discovery (TA0007) 

Once they have established a foothold and gained some level of privilege, Akira operators conduct extensive discovery to map the network and identify high-value targets. They use a combination of built-in Windows commands like nltest and net to gather information about the domain, its trusts, and administrative groups. They also deploy network scanning tools such as Advanced IP Scanner and Netscan to identify active systems and open ports [97]. For a deeper understanding of the Active Directory environment, they have been known to use tools like AdFind and BloodHound [102]. 

 

Lateral Movement (TA0008) 

To spread across the network and reach critical assets, Akira affiliates primarily use the credentials they have stolen. They frequently leverage the Remote Desktop Protocol (RDP) to connect to other systems, which can blend in with normal administrative activity [97]. They also use tools like PsExec and Impacket's wmiexec to execute commands and payloads remotely. In some cases, they have been observed deploying the ransomware via network shares and remote backup services. 

 

Collection (TA0009 - For Double Extortion) 

As part of their double-extortion strategy, Akira affiliates systematically collect sensitive data before encryption. They target a wide range of information, including financial records, intellectual property, and personal data. After identifying valuable files on local systems and network shares, they often archive the collected data using tools like WinRAR and 7-Zip [97]. This compression makes the data easier and faster to exfiltrate. In some instances, they have been observed accessing and downloading information from SharePoint instances [103]. 

 

Exfiltration (TA0010 - For Double Extortion) 

With the sensitive data collected and archived, the final step before encryption is to exfiltrate it to attacker-controlled infrastructure. Akira operators have been observed using a variety of tools for this purpose, including FileZilla, WinSCP, and Rclone [96]. These tools often use encrypted protocols like FTP or leverage legitimate cloud storage services, which helps to mask the data theft and bypass network security controls. They have also been known to use the command-line tool Ngrok to create secure tunnels to their servers for data exfiltration [100]. 

 

Command and Control (TA0011) 

Throughout the attack, Akira affiliates maintain command and control (C2) to manage their tools and orchestrate the intrusion. They often use legitimate remote access software like AnyDesk and RustDesk for this purpose [96]. They have also been observed using Cloudflare's tunneling tool, Cloudflared, to create a persistent and secure C2 channel. This use of legitimate tools helps their C2 traffic blend in with normal network activity, making it harder to detect. Their Tor-based leak site serves as the final C2 point for ransom negotiations and data publication [98]. 

 

Impact (TA0040) 

The ultimate goal of an Akira attack is to disrupt the victim's operations and extort a ransom payment. This is achieved primarily through Data Encrypted for Impact. The ransomware uses a hybrid encryption scheme, combining the ChaCha20 stream cipher with an RSA public-key cryptosystem to encrypt files [100]. Encrypted files are typically appended with the ".akira" extension, although the "Megazord" variant uses ".powerranges" To hinder recovery efforts, the ransomware deletes Volume Shadow Copies using PowerShell commands and Windows Management Instrumentation (WMI) [99]. 

 

Behavioral Patterns and Evolution 

Akira's rapid rise and evolution demonstrate key trends in the ransomware ecosystem. Emerging in March 2023, the group quickly gained notoriety and is believed to have affiliations with the defunct Conti ransomware gang, suggesting a lineage of experienced operators [98]. The group operates a RaaS model, allowing for a broad range of attacks carried out by various affiliates. A notable characteristic is their retro-themed Tor leak site, which provides a unique interface for interacting with victims. Akira has shown adaptability by developing different ransomware variants, including one written in C++ and another in Rust (dubbed "Megazord"), as well as a Linux variant to target VMware ESXi servers [100]. They also employ a double extortion model, adding pressure on victims by threatening to release stolen data. 

 

Implications for Proactive Defense 

Defending against Akira ransomware requires a multi-layered security approach. A critical first step is to secure remote access points with strong passwords and multi-factor authentication (MFA), particularly for VPNs [97]. Regular patching of all software and systems is essential to close the vulnerabilities that Akira's affiliates exploit. Network segmentation can help contain a breach and prevent lateral movement. Implementing robust backup and recovery strategies, including offline and immutable backups, is crucial for restoring operations without paying a ransom. Advanced security solutions like Endpoint Detection and Response (EDR) can help detect the behavioral indicators of an attack, such as credential dumping and suspicious process execution [100]. Lastly, employee training on phishing and social engineering awareness can help prevent initial access. 