package net.kigawa.admin.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * dmidecode/lsblkパーサーの検証(admin-panel#156)。Proxmox実機へのSSHは
 * 認証情報が必要でCIでは実行できないため、純粋関数だけを固定サンプルで検証する。
 */
class ProxmoxSshInventoryTest {

    private val slotSample = """
Handle 0x0009, DMI type 9, 17 bytes
System Slot Information
	Designation: PCIEX16_1
	Type: x16 PCI Express
	Current Usage: In Use
	Length: Long
	ID: 1
	Bus Address: 0000:00:01.0
	Data Bus Width: 16x / x16

Handle 0x000a, DMI type 9, 17 bytes
System Slot Information
	Designation: PCIEX1_1
	Type: x1 PCI Express
	Current Usage: Available
	Length: Short
	ID: 2
	Bus Address: 0000:00:1c.0
	Data Bus Width: 1x / x1

""".trimIndent()

    @Test
    fun `parses slot usage into free and used`() {
        val slots = parseDmidecodeSlots(slotSample)
        assertEquals(2, slots.size)

        val used = slots[0]
        assertEquals("PCIEX16_1", used.designation)
        assertEquals("x16 PCI Express", used.type)
        assertEquals("16x / x16", used.width)
        assertEquals("In Use", used.usage)
        assertFalse(used.free)

        val free = slots[1]
        assertEquals("PCIEX1_1", free.designation)
        assertEquals("Available", free.usage)
        assertTrue(free.free)
    }

    private val memorySample = """
Handle 0x0010, DMI type 16, 23 bytes
Physical Memory Array
	Location: System Board Or Motherboard
	Maximum Capacity: 128 GB

Handle 0x0018, DMI type 17, 40 bytes
Memory Device
	Array Handle: 0x0010
	Size: 16 GB
	Locator: DIMM_A1
	Bank Locator: BANK 0
	Type: DDR4
	Speed: 3200 MT/s

Handle 0x0019, DMI type 17, 40 bytes
Memory Device
	Array Handle: 0x0010
	Size: No Module Installed
	Locator: DIMM_A2
	Bank Locator: BANK 1
	Type: Unknown
	Speed: Unknown

""".trimIndent()

    @Test
    fun `parses memory devices skipping non-device records`() {
        val slots = parseDmidecodeMemory(memorySample)
        // Physical Memory Arrayレコードは除外され、Memory Deviceのみ2件
        assertEquals(2, slots.size)

        val populated = slots[0]
        assertEquals("DIMM_A1", populated.locator)
        assertEquals(16384L, populated.sizeMb)
        assertEquals("DDR4", populated.memType)
        assertEquals("3200 MT/s", populated.speed)
        assertFalse(populated.free)

        val empty = slots[1]
        assertEquals("DIMM_A2", empty.locator)
        assertNull(empty.sizeMb)
        assertNull(empty.memType)
        assertTrue(empty.free)
    }

    @Test
    fun `parses memory size units`() {
        assertEquals(16384L, parseMemorySizeMb("16 GB"))
        assertEquals(1024L, parseMemorySizeMb("1024 MB"))
        assertEquals(1048576L, parseMemorySizeMb("1 TB"))
        assertNull(parseMemorySizeMb("No Module Installed"))
        assertNull(parseMemorySizeMb("bogus"))
    }

    private val lsblkSample = """
{"blockdevices": [
   {"name":"sda", "size":1000204886016, "model":"Samsung SSD 870 ", "type":"disk"},
   {"name":"nvme0n1", "size":1000204886016, "model":"Samsung SSD 990 PRO", "type":"disk"},
   {"name":"sda1", "size":1073741824, "model":null, "type":"part"}
]}""".trimIndent()

    @Test
    fun `parses lsblk json keeping disks only`() {
        val disks = parseLsblkJson(lsblkSample)
        // partは除外され、diskのみ2件。modelの前後空白は除去される。
        assertEquals(2, disks.size)
        assertEquals("sda", disks[0].name)
        assertEquals(1000204886016L, disks[0].sizeBytes)
        assertEquals("Samsung SSD 870", disks[0].model)
        assertEquals("nvme0n1", disks[1].name)
    }

    @Test
    fun `returns empty on broken lsblk output`() {
        assertTrue(parseLsblkJson("not json").isEmpty())
    }

    @Test
    fun `keeps disk with null model instead of dropping all`() {
        // "model": null の1件があるとjsonPrimitiveが例外になり、外側catchで
        // 全件消失していた回帰ケース。null-modelの1件だけmodel=nullで保持する。
        val json = """
{"blockdevices": [
   {"name":"sda", "size":1000204886016, "model":null, "type":"disk"},
   {"name":"sdb", "size":500107862016, "model":"WDC WD5000", "type":"disk"}
]}""".trimIndent()
        val disks = parseLsblkJson(json)
        assertEquals(2, disks.size)
        assertEquals("sda", disks[0].name)
        assertNull(disks[0].model)
        assertEquals(1000204886016L, disks[0].sizeBytes)
        assertEquals("WDC WD5000", disks[1].model)
    }

    @Test
    fun `excludes virtual disks`() {
        // nbd/rbd/loop/dm-/mdは物理ディスクの意味論を持たないため除外する
        val json = """
{"blockdevices": [
   {"name":"sda", "size":1000204886016, "model":"Samsung SSD 870", "type":"disk"},
   {"name":"nbd0", "size":10737418240, "model":null, "type":"disk"},
   {"name":"rbd0", "size":10737418240, "model":null, "type":"disk"},
   {"name":"loop0", "size":1048576, "model":null, "type":"disk"},
   {"name":"dm-0", "size":10737418240, "model":null, "type":"disk"},
   {"name":"md0", "size":2000406220800, "model":null, "type":"disk"}
]}""".trimIndent()
        val disks = parseLsblkJson(json)
        assertEquals(1, disks.size)
        assertEquals("sda", disks[0].name)
    }

    @Test
    fun `parses memory with PTY-doubled line breaks`() {
        // SSH PTY(+sudo use_pty)経由では改行が\r\r\nに二重化されることを
        // 実機odダンプで確認。このままだと空行だらけでレコード判定が崩れるため、
        // パーサー入口で正規化する。位置ではなく内容で種別判定することと合わせた回帰検証。
        val ptyOutput = "Handle 0x0013, DMI type 17, 92 bytes\r\r\nMemory Device\r\r\n\tSize: 16 GB\r\r\n\tLocator: DIMM_A1\r\r\n\tType: DDR4\r\r\n\tSpeed: 2667 MT/s\r\r\n"
        val slots = parseDmidecodeMemory(ptyOutput)
        assertEquals(1, slots.size)
        assertEquals("DIMM_A1", slots[0].locator)
        assertEquals(16384L, slots[0].sizeMb)
        assertFalse(slots[0].free)
    }

    @Test
    fun `detects virtualized product names`() {
        assertTrue(isVirtualProductName("KVM"))
        assertTrue(isVirtualProductName("Standard PC (QEMU + KVM)"))
        assertTrue(isVirtualProductName("VMware Virtual Platform"))
        assertTrue(isVirtualProductName("VirtualBox"))
        assertTrue(isVirtualProductName("Virtual Machine"))
        assertTrue(isVirtualProductName("Microsoft Corporation"))
        assertTrue(isVirtualProductName("Amazon EC2"))
        assertTrue(isVirtualProductName("Google Compute Engine"))
        assertFalse(isVirtualProductName("PowerEdge R6515"))
        assertFalse(isVirtualProductName("ProLiant DL360 Gen10"))
        // QEMU系の定番プロダクト名(ManufacturerではなくProduct名に出る)
        assertTrue(isVirtualProductName("Standard PC (i440FX + PIIX, 1996)"))
        assertTrue(isVirtualProductName("Standard PC (Q35 + ICH9, 2009)"))
        assertFalse(isVirtualProductName("To Be Filled By O.E.M."))
    }

    private val dfSample = """
Filesystem       1-blocks        Used   Available Capacity Mounted on
/dev/dm-0      41152817152  8123456789 30942940963      21% /
/dev/nvme0n1p1   104857600    10485760   94371840      10% /boot/efi
/dev/nvme1n1 1000204886016 500102443008 499002443008     51% /mnt/backup disk
/dev/dm-0      41152817152  8123456789 30942940963      21% /
""".trimIndent()

    @Test
    fun `parses df output skipping header and duplicate mountpoints`() {
        val usages = parseDfUsage(dfSample)
        // ヘッダ行は落ち、重複した"/"は先勝ちで1件になる
        assertEquals(3, usages.size)

        val root = usages[0]
        assertEquals("/", root.mountpoint)
        assertEquals(41152817152L, root.sizeBytes)
        assertEquals(8123456789L, root.usedBytes)
        assertEquals(30942940963L, root.availBytes)
        assertEquals(21, root.percent)

        // マウントポイントに空白が含まれる行は6列目以降を結合して扱う
        val backup = usages[2]
        assertEquals("/mnt/backup disk", backup.mountpoint)
        assertEquals(1000204886016L, backup.sizeBytes)
        assertEquals(51, backup.percent)
    }

    private val dfFallbackSample = """
Filesystem     1-blocks     Used Available Capacity Mounted on
udev           16328260        0  16328260       0% /dev
tmpfs           3271364     6148   3265216       1% /run
overlay       41152817152 8123456789 30942940963      21% /var/lib/docker/overlay2/abc/merged
/dev/loop0      128835     128835         0      100% /snap/core20/1234
/dev/mapper/pve-root 101703636 48123456  48401265       50% /
/dev/mapper/pve-root 101703636 48123456  48401265       50% /var/lib/containerd/sandboxes/abc
df: /mnt/broken: Permission denied
""".trimIndent()

    @Test
    fun `drops pseudo filesystems and error lines from fallback df output`() {
        // df -x 非対応環境向けの保険。tmpfs/overlay/snap等とエラー行は対象外で、
        // 本体のルートのみ残る。同一デバイスの多重マウント(bind mount)も同じ使用率の
        // ため落とす。PTY経由の改行多重化(\r\r\n)も同時に検証する。
        val usages = parseDfUsage(dfFallbackSample.replace("\n", "\r\r\n"))
        assertEquals(1, usages.size)
        assertEquals("/", usages[0].mountpoint)
        assertEquals(50, usages[0].percent)

        // ヘッダ行やエラー行しかない出力は空(グレースフルに扱われる)
        assertTrue(parseDfUsage("Filesystem 1-blocks Used Available Capacity Mounted on").isEmpty())
    }

    @Test
    fun `parses real worker3 dmidecode memory output`() {
        // worker3実機のdmidecode 3.5出力(92バイト拡張レコード)。本番で空になった回帰検証用。
        val output = """
# dmidecode 3.5
Getting SMBIOS data from sysfs.
SMBIOS 3.3.0 present.

Handle 0x000D, DMI type 16, 23 bytes
Physical Memory Array
	Location: System Board Or Motherboard
	Use: System Memory
	Error Correction Type: None
	Maximum Capacity: 64 GB
	Error Information Handle: Not Provided
	Number Of Devices: 4

Handle 0x0013, DMI type 17, 92 bytes
Memory Device
	Array Handle: 0x000D
	Error Information Handle: Not Provided
	Total Width: 64 bits
	Data Width: 64 bits
	Size: 16 GB
	Form Factor: DIMM
	Set: None
	Locator: Controller0-ChannelA-DIMM0
	Bank Locator: BANK 0
	Type: DDR4
	Type Detail: Synchronous
	Speed: 2667 MT/s
	Manufacturer: 0x09EE
	Serial Number: MASKED
	Asset Tag: MASKED
	Part Number: MASKED
	Rank: 1
	Configured Memory Speed: 2133 MT/s
	Minimum Voltage: 1.2 V
	Maximum Voltage: 1.2 V
	Configured Voltage: 1.2 V
	Memory Technology: DRAM
	Memory Operating Mode Capability: Volatile memory
	Firmware Version: Not Specified
	Module Manufacturer ID: Bank 10, Hex 0xEE
	Module Product ID: Unknown
	Memory Subsystem Controller Manufacturer ID: Unknown
	Memory Subsystem Controller Product ID: Unknown
	Non-Volatile Size: None
	Volatile Size: 16 GB
	Cache Size: None
	Logical Size: None

Handle 0x0014, DMI type 17, 92 bytes
Memory Device
	Array Handle: 0x000D
	Error Information Handle: Not Provided
	Total Width: 64 bits
	Data Width: 64 bits
	Size: 16 GB
	Form Factor: DIMM
	Set: None
	Locator: Controller0-ChannelA-DIMM1
	Bank Locator: BANK 0
	Type: DDR4
	Type Detail: Synchronous
	Speed: 2133 MT/s
	Manufacturer: Corsair
	Serial Number: MASKED
	Asset Tag: MASKED
	Part Number: MASKED
	Rank: 1
	Configured Memory Speed: 2133 MT/s
	Minimum Voltage: 1.2 V
	Maximum Voltage: 1.2 V
	Configured Voltage: 1.2 V
	Memory Technology: DRAM
	Memory Operating Mode Capability: Volatile memory
	Firmware Version: Not Specified
	Module Manufacturer ID: Bank 3, Hex 0x9E
	Module Product ID: Unknown
	Memory Subsystem Controller Manufacturer ID: Unknown
	Memory Subsystem Controller Product ID: Unknown
	Non-Volatile Size: None
	Volatile Size: 16 GB
	Cache Size: None
	Logical Size: None

Handle 0x0015, DMI type 17, 92 bytes
Memory Device
	Array Handle: 0x000D
	Error Information Handle: Not Provided
	Total Width: 64 bits
	Data Width: 64 bits
	Size: 16 GB
	Form Factor: DIMM
	Set: None
	Locator: Controller0-ChannelB-DIMM0
	Bank Locator: BANK 1
	Type: DDR4
	Type Detail: Synchronous
	Speed: 2667 MT/s
	Manufacturer: 0x09EE
	Serial Number: MASKED
	Asset Tag: MASKED
	Part Number: MASKED
	Rank: 1
	Configured Memory Speed: 2133 MT/s
	Minimum Voltage: 1.2 V
	Maximum Voltage: 1.2 V
	Configured Voltage: 1.2 V
	Memory Technology: DRAM
	Memory Operating Mode Capability: Volatile memory
	Firmware Version: Not Specified
	Module Manufacturer ID: Bank 10, Hex 0xEE
	Module Product ID: Unknown
	Memory Subsystem Controller Manufacturer ID: Unknown
	Memory Subsystem Controller Product ID: Unknown
	Non-Volatile Size: None
	Volatile Size: 16 GB
	Cache Size: None
	Logical Size: None

Handle 0x0016, DMI type 17, 92 bytes
Memory Device
	Array Handle: 0x000D
	Error Information Handle: Not Provided
	Total Width: 64 bits
	Data Width: 64 bits
	Size: 16 GB
	Form Factor: DIMM
	Set: None
	Locator: Controller0-ChannelB-DIMM1
	Bank Locator: BANK 1
	Type: DDR4
	Type Detail: Synchronous
	Speed: 2133 MT/s
	Manufacturer: Corsair
	Serial Number: MASKED
	Asset Tag: MASKED
	Part Number: MASKED
	Rank: 1
	Configured Memory Speed: 2133 MT/s
	Minimum Voltage: 1.2 V
	Maximum Voltage: 1.2 V
	Configured Voltage: 1.2 V
	Memory Technology: DRAM
	Memory Operating Mode Capability: Volatile memory
	Firmware Version: Not Specified
	Module Manufacturer ID: Bank 3, Hex 0x9E
	Module Product ID: Unknown
	Memory Subsystem Controller Manufacturer ID: Unknown
	Memory Subsystem Controller Product ID: Unknown
	Non-Volatile Size: None
	Volatile Size: 16 GB
	Cache Size: None
	Logical Size: None


""".trimIndent()
        val slots = parseDmidecodeMemory(output)
        assertEquals(4, slots.size)
        assertEquals("Controller0-ChannelA-DIMM0", slots[0].locator)
        assertEquals(16384L, slots[0].sizeMb)
        assertEquals("DDR4", slots[0].memType)
        assertFalse(slots[0].free)
    }
}
