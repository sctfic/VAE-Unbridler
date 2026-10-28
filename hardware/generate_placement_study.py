"""Make a mechanical-only KiCad PCB study. NOT a manufacturing board.

Run with KiCad's Python: bin/python.exe hardware/generate_placement_study.py.
The footprints are placement approximations. Only two connector nets are
assigned; there are no tracks and most components are missing.
"""
from pathlib import Path
import pcbnew as pcb

ROOT = Path(__file__).parent / "kicad"
LIB = Path(r"C:\Program Files\KiCad\10.0\share\kicad\footprints")
OUT = ROOT / "placement_study_NOT_FOR_FABRICATION.kicad_pcb"


def pt(x, y):
    return pcb.VECTOR2I(pcb.FromMM(x), pcb.FromMM(y))


def footprint(board, library, name, ref, value, x, y, angle=0):
    item = pcb.FootprintLoad(str(LIB / (library + ".pretty")), name)
    if item is None:
        raise RuntimeError(f"Missing footprint {library}:{name}")
    item.SetReference(ref)
    item.SetValue(value)
    item.SetPosition(pt(x, y))
    item.SetOrientationDegrees(angle)
    item.Reference().SetVisible(False)
    item.Value().SetVisible(False)
    board.Add(item)
    return item


def main():
    board = pcb.BOARD()
    board.SetCopperLayerCount(4)
    board.GetDesignSettings().SetBoardThickness(pcb.FromMM(0.8))
    for a, b in [((0, 0), (30, 0)), ((30, 0), (30, 20)),
                 ((30, 20), (0, 20)), ((0, 20), (0, 0))]:
        edge = pcb.PCB_SHAPE()
        edge.SetShape(pcb.SHAPE_T_SEGMENT)
        edge.SetStart(pt(*a))
        edge.SetEnd(pt(*b))
        edge.SetLayer(pcb.Edge_Cuts)
        edge.SetWidth(pcb.FromMM(0.05))
        board.Add(edge)

    usb = footprint(board, "Connector_USB", "USB_C_Receptacle_HRO_TYPE-C-31-M-12",
                    "J1", "TYPE-C-31-M-12", 4.1, 10, -90)
    # Give overlapping USB pads their actual common nets. This does not make
    # the PCB electrically complete; no other net assignments or routes exist.
    ground = pcb.NETINFO_ITEM(board, "GND")
    vbus = pcb.NETINFO_ITEM(board, "USB_VBUS")
    board.Add(ground)
    board.Add(vbus)
    for pad in usb.Pads():
        if pad.GetNumber() in ("A1", "A12", "B1", "B12", "SH"):
            pad.SetNet(ground)
        elif pad.GetNumber() in ("A4", "A9", "B4", "B9"):
            pad.SetNet(vbus)
    footprint(board, "Package_DFN_QFN", "QFN-56-1EP_7x7mm_P0.4mm_EP4x4mm",
              "U1", "ESP32-S3FH4R2", 15.7, 10)
    footprint(board, "RF_Antenna", "Johanson_2450AT18x100_2400-2500Mhz",
              "AE1", "2450AT18A0100001E", 27.4, 3.0)
    # Approximate footprint only: compare land pattern to selected data sheet.
    switch = footprint(board, "Package_SO", "SOP-4_3.8x4.1mm_P2.54mm",
                       "U5", "AQY222R1SX - FOOTPRINT TO VERIFY", 24.5, 15.0)
    switch.Flip(switch.GetPosition(), False)
    footprint(board, "Inductor_SMD", "L_Bourns_SRU5016_5.2x5.2mm",
              "L1", "3.9uH - FOOTPRINT TO VERIFY", 25.0, 15.5)
    footprint(board, "Package_TO_SOT_SMD", "TSOT-23-6",
              "U2", "AP63203WU-7", 11.4, 17.2)
    footprint(board, "Package_TO_SOT_SMD", "SOT-23-5",
              "U3", "AP2112K-3.3TRG1", 11.8, 4.2)
    footprint(board, "Package_TO_SOT_SMD", "SOT-583-8",
              "U4", "TPS2116DRLR", 22.2, 10.8)
    driver = footprint(board, "Package_TO_SOT_SMD", "SOT-23",
                       "Q1", "MMBT3904", 17.5, 17.0)
    driver.Flip(driver.GetPosition(), False)
    footprint(board, "Crystal", "Crystal_SMD_2016-4Pin_2.0x1.6mm",
              "Y1", "40MHz", 21.4, 7.0)

    # Six unconnected through-hole solder pads: placeholder mechanical positions.
    for i, x in enumerate((8, 11, 14, 17, 20, 23), 1):
        fp = pcb.FOOTPRINT(board)
        fp.SetReference(f"H{i}")
        fp.SetValue("WIRE HOLE - NO NET")
        fp.SetPosition(pt(x, 1.5))
        fp.Reference().SetVisible(False)
        fp.Value().SetVisible(False)
        pad = pcb.PAD(fp)
        pad.SetNumber("1")
        pad.SetAttribute(pcb.PAD_ATTRIB_PTH)
        pad.SetShape(pcb.PAD_SHAPE_CIRCLE)
        pad.SetSize(pt(1.9, 1.9))
        pad.SetDrillSize(pt(1.0, 1.0))
        pad.SetLayerSet(pcb.LSET.AllCuMask())
        fp.Add(pad)
        board.Add(fp)

    text = pcb.PCB_TEXT(board)
    text.SetText("DRAFT - NO TRACKS")
    text.SetPosition(pt(15, 19.3))
    text.SetTextSize(pt(0.8, 0.8))
    text.SetTextThickness(pcb.FromMM(0.12))
    text.SetLayer(pcb.F_Fab)
    board.Add(text)
    ROOT.mkdir(parents=True, exist_ok=True)
    pcb.SaveBoard(str(OUT), board)
    print(OUT)


if __name__ == "__main__":
    main()
