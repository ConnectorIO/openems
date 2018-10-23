import { Component, Input, Output, EventEmitter } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { AbstractSection, SvgSquarePosition, SvgSquare, EnergyFlow, SvgEnergyFlow } from './abstractsection.component';


@Component({
    selector: '[gridsection]',
    templateUrl: './section.component.html'
})
export class GridSectionComponent extends AbstractSection {

    constructor(translate: TranslateService) {
        super('General.Grid', "left", 226, 314, "#1d1d1d", translate);
    }

    protected gridMode: number

    public updateGridValue(buyAbsolute: number, sellAbsolute: number, valueRatio: number, sumBuyRatio: number, sumSellRatio: number, gridMode: number) {
        console.log('RATIO BEFORE', valueRatio)
        valueRatio = valueRatio / 2; // interval from -50 to 50
        this.gridMode = gridMode
        if (gridMode && this.square) {
            this.square.image.image = "assets/img/" + this.getImagePath();
        }
        if (buyAbsolute != null && buyAbsolute > 0) {
            this.name = this.translate.instant('General.GridBuy');
            super.updateGrid(buyAbsolute, valueRatio, sumBuyRatio * -1);
        } else if (sellAbsolute != null && sellAbsolute > 0) {
            this.name = this.translate.instant('General.GridSell');
            super.updateGrid(sellAbsolute, valueRatio, sumSellRatio);
        }
        else {
            this.name = this.translate.instant('General.Grid')
            super.updateGrid(0, 0, 0);
        }
        console.log('Grid ValueRatio 1:', valueRatio)
    }

    protected getSquarePosition(square: SvgSquare, innerRadius: number): SvgSquarePosition {
        let x = (innerRadius - 5) * (-1);
        let y = (square.length / 2) * (-1);
        return new SvgSquarePosition(x, y);
    }

    protected getImagePath(): string {
        if (this.gridMode == 2 || this.gridMode == 0) {
            return "offgrid.png"
        }
        else {
            return "grid.png"
        }
    }

    public getValueRatio(value: number) {
        if (value > 50) {
            return 50;
        } else if (value < -50) {
            return 50;
        }
        return value;
    }

    protected getValueStartAngle(): number {
        return (this.startAngle + this.endAngle) / 2;
    }

    protected getValueText(value: number): string {
        if (value == null || Number.isNaN(value)) {
            return "";
        }

        return value + " W";
    }

    protected initEnergyFlow(radius: number): EnergyFlow {
        return new EnergyFlow(radius, { x1: "100%", y1: "50%", x2: "0%", y2: "50%" });
    }

    protected getSvgEnergyFlow(ratio: number, r: number, v: number): SvgEnergyFlow {
        let p = {
            topLeft: { x: r * -1, y: v * -1 },
            middleLeft: { x: r * -1 + v, y: 0 },
            bottomLeft: { x: r * -1, y: v },
            topRight: { x: v * -1, y: v * -1 },
            bottomRight: { x: v * -1, y: v },
            middleRight: { x: 0, y: 0 }
        }
        if (ratio > 0) {
            // towards left
            p.topLeft.x = p.topLeft.x + v;
            p.middleLeft.x = p.middleLeft.x - v;
            p.bottomLeft.x = p.bottomLeft.x + v;
        }
        return p;
    }
}
