// maintenir Boot puis branche l'usb, lacher puis Uploader 
#include <Arduino.h>
#include "Freenove_WS2812_Lib_for_ESP32.h"

#define LEDS_PIN	21
Freenove_ESP32_WS2812 strip = Freenove_ESP32_WS2812(1, LEDS_PIN, 0, TYPE_GRB);

// https://www.circuitstate.com/pinouts/doit-esp32-devkit-v1-wifi-development-board-pinout-diagram-and-reference/#PNG
#define SENSOR_SWITCH_PIN 5
#define SENSOR_WRITE_PIN 6

double const Wheel = 2.360;// 2.478; // 2.242;
int Intervale_1kmh, Intervale_24kmh, Intervale_25kmh, Intervale_30kmh, Intervale_35kmh, Intervale_40kmh;
boolean MagnetPassed = true;

// put function declarations here:
void IRAM_ATTR MagnetPass();
void Pulse(unsigned long Now);
void SpeedColor(int Intervale);

unsigned long lastReadTicks=0;
unsigned long LastWriteTicks=0;
unsigned long NextWriteTicks=999999;

int LastWheelIntervale=0;
int NextTcheatIntervale=999999;

void setup() {
  // strip.begin();
  // strip.setBrightness(5);
  // strip.setLedColorData(0, 255, 0, 255);
  // strip.show();
  // btStop();
  // delay(100);

    // initialize the pushbutton pin as an pull-up input
    // the pull-up input pin will be HIGH when the switch is open and LOW when the switch is closed.
    pinMode(SENSOR_SWITCH_PIN, INPUT_PULLUP);
    pinMode(SENSOR_WRITE_PIN, OUTPUT);
    attachInterrupt(SENSOR_SWITCH_PIN, MagnetPass, RISING);
  
    Intervale_1kmh  = round(Wheel / 0.27778 * 1000);
    Intervale_24kmh = round(Wheel / 6.66667 * 1000);
    Intervale_25kmh = round(Wheel / 6.94444 * 1000);
    Intervale_30kmh = round(Wheel / 8.33333 * 1000);
    Intervale_35kmh = round(Wheel / 9.72222 * 1000);
    Intervale_40kmh = round(Wheel / 11.11111 * 1000);

  // strip.setLedColorData(0, 0, 0, 0);// Black
  // strip.setLedColorData(0, 255, 255, 255);// White
  // strip.setLedColorData(0, 255, 0, 0);// Red
  // strip.setLedColorData(0, 0, 255, 0);// Green
  // strip.setLedColorData(0, 0, 0, 255);// Blue
  // strip.setLedColorData(0, 255, 255, 0);// Yellow
  // strip.setLedColorData(0, 255, 153, 0);// Orange clair
  // strip.setLedColorData(0, 255, 102, 0);// Orange Foncé
  // strip.setLedColorData(0, 255, 0, 255);// Magenta
  // strip.setLedColorData(0, 0, 255, 255);// Cyan
}

void loop() {
  unsigned long Now = millis();
  
  if (MagnetPassed){
    MagnetPassed = false;
    if (LastWheelIntervale > Intervale_24kmh && Now-LastWriteTicks >= Intervale_24kmh) {   // entre O et 24km/h
      // if (Now-LastWriteTicks >= Intervale_24kmh){ // on reproduit dessuite 
        NextTcheatIntervale = 0;
      // } else {                                    // on reproduit dans quelque ms
        // NextTcheatIntervale = Intervale_24kmh-(Now-LastWriteTicks);
      // }
    } else {
      NextTcheatIntervale = Intervale_24kmh;
    }
    // SpeedColor(Now-LastWriteTicks);
    Serial.printf("Now:%i - NextTcheatIntervale:%ims  ", Now, NextTcheatIntervale);
  } 
  
  NextWriteTicks = LastWriteTicks + NextTcheatIntervale;

  if (NextWriteTicks <= Now){ // il est temps de faire une impulsion
    Pulse(Now);
    NextTcheatIntervale = Now + 15*60*1000; // 15min * 60sec * 1000ms
  } else {
    delay(1);
  }
}

void SpeedColor(int Intervale){
  int RealSpead = (Wheel/((double)Intervale/1000))*3.6;
  int R=0,V=0,B=0;
  if (RealSpead < 1) {
    // black
  } else if (RealSpead <= 25) {
    V=RealSpead*10+5;
    B=255-RealSpead*10;
  } else if (RealSpead <= 35) {
    R=(RealSpead-25)*25+5;
    V=255;
  } else if (RealSpead <= 35) {
    R=255;
    V=(RealSpead-35)*25+5;
  } else {
    R=255;
  }
  strip.setLedColorData(0, R, V, B);
  strip.show();

}
void Pulse(unsigned long Now){
  double RealSpead = (Wheel/((double)LastWheelIntervale/1000))*3.6;
  Serial.printf("\n              Now=%i\n", Now);
  Serial.printf("Last=%i => Next=%i\n", LastWriteTicks, NextWriteTicks);
  Serial.printf("Mesure:%ims = ", LastWheelIntervale);
  Serial.print(RealSpead,1);
  Serial.print("km/h Real\n");
  Serial.printf("   Ecoule:%ims = ",(Now-LastWriteTicks));
  Serial.print((Wheel/(((double)(Now-LastWriteTicks)/1000)))*3.6,1);
  Serial.print("km/h  TCHEAT\n\n");

  LastWriteTicks = Now;
  digitalWrite(SENSOR_WRITE_PIN, HIGH);
  delay(2);
  digitalWrite(SENSOR_WRITE_PIN, LOW);
}

void IRAM_ATTR MagnetPass() {
    unsigned long Now;
    Now = millis();
    if(Now-lastReadTicks > 100){ // pour eviter les allucinations / rebon
      LastWheelIntervale =  Now-lastReadTicks;
      lastReadTicks = Now;
      MagnetPassed = true;
    }
}