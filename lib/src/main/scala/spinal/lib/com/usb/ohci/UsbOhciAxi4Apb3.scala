package spinal.lib.com.usb.ohci

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb.sim.Apb3Driver
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3ToBmb}
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.bmb.sim.BmbDriver
import spinal.lib.bus.bmb.{Axi4SharedToBmb, BmbToAxi4SharedBridge}
import spinal.lib.com.usb.phy.UsbHubLsFs.CtrlCc
import spinal.lib.com.usb.phy.{UsbLsFsPhy, UsbPhyFsNativeIo}

object UsbOhciAxi4Apb3 extends App{
  var netlistDirectory = "."
  var netlistName = "UsbOhciAxi4Apb3"
  var portCount = 1
  var phyFrequency = 48000000
  var dmaWidth = 32
  var fifoBytes = 2048

  assert(new scopt.OptionParser[Unit]("VexRiscvLitexSmpClusterCmdGen") {
    help("help").text("prints this usage text")
    opt[String]("netlist-directory") action { (v, c) => netlistDirectory = v }
    opt[String]("netlist-name") action { (v, c) => netlistName = v }
    opt[Int]("port-count") action { (v, c) => portCount = v }
    opt[Int]("phy-frequency") action { (v, c) => phyFrequency = v }
    opt[Int]("dma-width") action { (v, c) => dmaWidth = v }
    opt[Int]("fifo-bytes") action { (v, c) => fifoBytes = v }
  }.parse(args, ()).isDefined)



  val p = UsbOhciParameter(
    noPowerSwitching = false,
    powerSwitchingMode = false,
    noOverCurrentProtection = false,
    powerOnToPowerGoodTime = 10,
    dataWidth = dmaWidth,
    portsConfig = List.fill(portCount)(OhciPortParameter()),
    fifoBytes = fifoBytes
  )

  SpinalConfig(
    globalPrefix = s"${netlistName}_",
    targetDirectory = netlistDirectory
  ).generateVerilog(
    UsbOhciAxi4Apb3(
      p,
      ClockDomain.external("ctrl"),
      ClockDomain.external("phy", frequency = FixedFrequency(phyFrequency Hz))
    ).setDefinitionName(netlistName)
  )

  import spinal.core.sim._
  SimConfig.withFstWave.compile(UsbOhciAxi4Apb3(
    p,
    ClockDomain.external("ctrl"),
    ClockDomain.external("phy", frequency = FixedFrequency(phyFrequency Hz))
  )).doSim{dut =>
    dut.frontCd.forkStimulus(10)
    dut.backCd.forkStimulus(22)

    val driver = Apb3Driver(dut.io.ctrl, dut.frontCd)
    dut.frontCd.waitSampling(10)
    println(f"${driver.read(0)}%x")
    dut.frontCd.waitSampling(10)
    println(f"${driver.read(0x34)}%x")
    driver.write(0x34,0x1234)
    println(f"${driver.read(0x34)}%x")
  }
}

case class UsbOhciAxi4Apb3(p : UsbOhciParameter, frontCd : ClockDomain, backCd : ClockDomain) extends Component {
  val ctrlParameter = Apb3Config(
    addressWidth = 12,
    dataWidth    = 32
  )

  val dmaParameter = BmbToAxi4SharedBridge.getAxi4Config(UsbOhci.dmaParameter(p))

  val io = new Bundle {
    val dma = master(Axi4(dmaParameter))
    val ctrl = slave(Apb3(ctrlParameter))
    val interrupt = out Bool()
    val usb = Vec(master(UsbPhyFsNativeIo()), p.portCount)
  }

  val front = frontCd on new Area {
    val dmaBridge = BmbToAxi4SharedBridge(UsbOhci.dmaParameter(p))
    dmaBridge.io.output.toAxi4() <> io.dma

    val ctrlBridge = new Apb3ToBmb(ctrlParameter)
    ctrlBridge.io.apb <> io.ctrl

    val ohci = UsbOhci(p, ctrlBridge.io.bmb.p)
    ohci.io.dma <> dmaBridge.io.input
    ohci.io.ctrl <> ctrlBridge.io.bmb
    ohci.io.interrupt <> io.interrupt
  }

  val back = backCd on new Area {
    val phy = UsbLsFsPhy(p.portCount)
    //  phy.io.usb  <> io.usb
    phy.io.management.map(e => e.overcurrent := False)
    val native = phy.io.usb.map(_.toNativeIo())
    val buffer = native.map(_.bufferized())
    io.usb <> Vec(buffer)
  }

  val cc = CtrlCc(p.portCount, frontCd, backCd)
  cc.input <> front.ohci.io.phy
  cc.output <> back.phy.io.ctrl
}
