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
    }
}
